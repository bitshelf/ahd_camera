// SPDX-License-Identifier: Apache-2.0
#include "CaptureIo.h"
#include <tinyxml2.h>
#include <linux/v4l2-subdev.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <map>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fstream>
#include <mutex>
#include <regex>
#include <set>

namespace ahd::capture {
namespace {
std::mutex lock;
std::vector<Node> configured;
int rawIoctl(int fd, unsigned long req, void* arg) { return ::ioctl(fd, req, arg); }
int fail(int error) { errno = error; return -1; }
bool devicePath(const std::string& s, bool timing = false) {
    return std::regex_match(s, std::regex(timing ? "/dev/v4l-subdev[0-9]+" : "/dev/video[0-9]+"));
}
std::string text(const tinyxml2::XMLElement* e, const char* key) {
    const char* value = e->Attribute(key);
    return value ? value : "";
}
bool nodeForFd(int fd, Node* result) {
    char path[256]{};
    const auto link = "/proc/self/fd/" + std::to_string(fd);
    const auto n = readlink(link.c_str(), path, sizeof(path) - 1);
    if (n < 0) return false;
    std::lock_guard guard(lock);
    for (const auto& node : configured) if (node.path == path) { *result = node; return true; }
    return false;
}
bool capForFd(int fd, v4l2_capability* cap) {
    if (rawIoctl(fd, VIDIOC_QUERYCAP, cap) < 0) return false;
    const auto bits = cap->capabilities & V4L2_CAP_DEVICE_CAPS ? cap->device_caps : cap->capabilities;
    return (bits & V4L2_CAP_STREAMING) && (bits & (V4L2_CAP_VIDEO_CAPTURE | V4L2_CAP_VIDEO_CAPTURE_MPLANE));
}
bool sourceGraphMatches(int videoFd,const Node& node) {
    if (node.mediaNode.empty()) return node.timingNode.empty();
    struct stat video{},decoder{};
    if (fstat(videoFd,&video) || lstat(node.timingNode.c_str(),&decoder) || !S_ISCHR(decoder.st_mode)) return false;
    const int fd=open(node.mediaNode.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if (fd<0) return false;
    media_v2_topology topology{};
    if (rawIoctl(fd,MEDIA_IOC_G_TOPOLOGY,&topology) || topology.num_entities>4096 ||
        topology.num_interfaces>4096 || topology.num_pads>8192 || topology.num_links>16384) {
        close(fd);return false;
    }
    std::vector<media_v2_entity> entities(topology.num_entities);
    Graph graph{std::vector<media_v2_interface>(topology.num_interfaces),
                std::vector<media_v2_pad>(topology.num_pads),std::vector<media_v2_link>(topology.num_links)};
    topology.ptr_entities=reinterpret_cast<uintptr_t>(entities.data());
    topology.ptr_interfaces=reinterpret_cast<uintptr_t>(graph.interfaces.data());
    topology.ptr_pads=reinterpret_cast<uintptr_t>(graph.pads.data());
    topology.ptr_links=reinterpret_cast<uintptr_t>(graph.links.data());
    const auto version=topology.topology_version;
    const int result=rawIoctl(fd,MEDIA_IOC_G_TOPOLOGY,&topology);
    close(fd);
    if (result || version!=topology.topology_version) return false;
    return graphConnects(graph,major(video.st_rdev),minor(video.st_rdev),
                         major(decoder.st_rdev),minor(decoder.st_rdev));
}
v4l2_fract signalInterval(const Node& node) {
    if (node.timingNode.empty()) return {};
    std::ifstream name("/sys/class/video4linux/" + node.timingNode.substr(5) + "/name");
    std::string actual;
    std::getline(name, actual);
    if (actual != node.timingName) return {};
    int fd = open(node.timingNode.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return {};
    v4l2_subdev_frame_interval interval{};
    interval.pad = node.timingPad;
    const int result = rawIoctl(fd, VIDIOC_SUBDEV_G_FRAME_INTERVAL, &interval);
    close(fd);
    return result == 0 ? interval.interval : v4l2_fract{};
}
}
bool graphConnects(const Graph& graph,uint32_t videoMajor,uint32_t videoMinor,
                   uint32_t decoderMajor,uint32_t decoderMinor) {
    uint32_t videoInterface=0,decoderInterface=0;
    for (const auto& i:graph.interfaces) {
        if (i.devnode.major==videoMajor && i.devnode.minor==videoMinor) videoInterface=i.id;
        if (i.devnode.major==decoderMajor && i.devnode.minor==decoderMinor) decoderInterface=i.id;
    }
    if (!videoInterface || !decoderInterface || videoInterface==decoderInterface) return false;
    uint32_t videoEntity=0,decoderEntity=0;
    for (const auto& l:graph.links) if ((l.flags & MEDIA_LNK_FL_LINK_TYPE)==MEDIA_LNK_FL_INTERFACE_LINK) {
        if (l.source_id==videoInterface) videoEntity=l.sink_id;
        if (l.source_id==decoderInterface) decoderEntity=l.sink_id;
    }
    if (!videoEntity || !decoderEntity) return false;
    std::map<uint32_t,uint32_t> pads;
    for (const auto& p:graph.pads) { const uint32_t id=p.id; pads[id]=p.entity_id; }
    std::map<uint32_t,std::vector<uint32_t>> upstream;
    for (const auto& l:graph.links) {
        if ((l.flags & MEDIA_LNK_FL_LINK_TYPE)!=MEDIA_LNK_FL_DATA_LINK || !(l.flags & MEDIA_LNK_FL_ENABLED)) continue;
        const uint32_t source=l.source_id,sink=l.sink_id;
        auto a=pads.find(source),b=pads.find(sink);
        if (a!=pads.end() && b!=pads.end()) upstream[b->second].push_back(a->second);
    }
    std::vector<uint32_t> pending{videoEntity};std::set<uint32_t> visited;
    while (!pending.empty()) {
        const auto entity=pending.back();pending.pop_back();
        if (entity==decoderEntity) return true;
        if (!visited.insert(entity).second) continue;
        for (auto parent:upstream[entity]) pending.push_back(parent);
    }
    return false;
}
Config loadConfig(const char* path) {
    Config config;
    tinyxml2::XMLDocument document;
    if (document.LoadFile(path) != tinyxml2::XML_SUCCESS) return config;
    const auto* root = document.FirstChildElement("AhdCameraProvider");
    if (!root) return config;
    config.maxConcurrent = root->UnsignedAttribute("maxConcurrent", 1);
    config.maxPixelRate = root->Unsigned64Attribute("maxPixelRate", 0);
    config.resourceCost = root->IntAttribute("resourceCost", 100);
    if (!config.maxConcurrent || config.maxConcurrent > 16 ||
        config.resourceCost < 0 || config.resourceCost > 100) return {};
    std::set<std::string> ids, paths;
    for (auto* e = root->FirstChildElement("Camera"); e; e = e->NextSiblingElement("Camera")) {
        Node node{text(e,"id"),text(e,"path"),text(e,"driver"),text(e,"card"),text(e,"bus"),
                  text(e,"timingNode"),text(e,"timingName"),e->UnsignedAttribute("timingPad",0),text(e,"mediaNode")};
        if (!std::regex_match(node.id,std::regex("ahd-[a-zA-Z0-9_-]{1,48}")) ||
            !devicePath(node.path) || node.driver.empty() || node.card.empty() || node.bus.empty() ||
            (!node.timingNode.empty() && (!devicePath(node.timingNode,true) || node.timingName.empty() ||
                !std::regex_match(node.mediaNode,std::regex("/dev/media[0-9]+")))) ||
            !ids.insert(node.id).second || !paths.insert(node.path).second || paths.size() > 16) return {};
        config.nodes.push_back(node);
    }
    if (config.maxConcurrent > config.nodes.size() ||
        (config.maxConcurrent > 1 && !config.maxPixelRate)) return {};
    return config;
}
void setNodes(const std::vector<Node>& nodes) { std::lock_guard guard(lock); configured = nodes; }
bool identityMatches(int fd, const Node& node) {
    struct stat st{};
    v4l2_capability cap{};
    if (fstat(fd,&st) || !S_ISCHR(st.st_mode) || !capForFd(fd,&cap)) return false;
    auto field = [](const __u8* p, size_t n) { return std::string(reinterpret_cast<const char*>(p),strnlen(reinterpret_cast<const char*>(p),n)); };
    return field(cap.driver,sizeof(cap.driver)) == node.driver &&
           field(cap.card,sizeof(cap.card)) == node.card && field(cap.bus_info,sizeof(cap.bus_info)) == node.bus;
}
int openNode(const std::string& path) {
    Node node;
    {
        std::lock_guard guard(lock);
        auto it = std::find_if(configured.begin(), configured.end(), [&](const auto& n) { return n.path == path; });
        if (it == configured.end()) return fail(EACCES);
        node = *it;
    }
    const int fd = open(path.c_str(), O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return fd;
    if (!identityMatches(fd,node) || !sourceGraphMatches(fd,node)) { close(fd); return fail(ENODEV); }
    if (!node.timingNode.empty()) {
        const auto rate = signalInterval(node);
        if (!rate.numerator || !rate.denominator) { close(fd); return fail(ENODATA); }
    }
    return fd;
}
int translateControl(int fd, bool mp, unsigned long req, void* arg, Ioctl raw) {
    if (!mp) return raw(fd,req,arg);
    if (req == VIDIOC_ENUM_FMT) {
        auto* f = static_cast<v4l2_fmtdesc*>(arg); auto copy = *f;
        copy.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
        int ret = raw(fd,req,&copy); if (!ret) { copy.type = f->type; *f = copy; } return ret;
    }
    if (req == VIDIOC_G_FMT || req == VIDIOC_S_FMT || req == VIDIOC_TRY_FMT) {
        auto* f = static_cast<v4l2_format*>(arg); v4l2_format copy{};
        copy.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
        if (req != VIDIOC_G_FMT) {
            copy.fmt.pix_mp.width = f->fmt.pix.width; copy.fmt.pix_mp.height = f->fmt.pix.height;
            copy.fmt.pix_mp.pixelformat = f->fmt.pix.pixelformat; copy.fmt.pix_mp.field = V4L2_FIELD_NONE;
            copy.fmt.pix_mp.num_planes = 1;
        }
        int ret = raw(fd,req,&copy); if (ret) return ret;
        if (copy.fmt.pix_mp.num_planes != 1) return fail(ENOTSUP);
        f->fmt.pix = {};
        f->fmt.pix.width = copy.fmt.pix_mp.width; f->fmt.pix.height = copy.fmt.pix_mp.height;
        f->fmt.pix.pixelformat = copy.fmt.pix_mp.pixelformat; f->fmt.pix.field = copy.fmt.pix_mp.field;
        f->fmt.pix.bytesperline = copy.fmt.pix_mp.plane_fmt[0].bytesperline;
        f->fmt.pix.sizeimage = copy.fmt.pix_mp.plane_fmt[0].sizeimage;
        f->fmt.pix.colorspace = copy.fmt.pix_mp.colorspace;
        return 0;
    }
    if (req == VIDIOC_QUERYBUF || req == VIDIOC_QBUF || req == VIDIOC_DQBUF) {
        auto* b = static_cast<v4l2_buffer*>(arg); auto copy = *b;
        v4l2_plane plane{}; plane.length = b->length; plane.bytesused = b->bytesused;
        copy.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE; copy.length = 1; copy.m.planes = &plane;
        int ret = raw(fd,req,&copy); if (ret) return ret;
        if (copy.length != 1 || plane.data_offset) return fail(ENOTSUP);
        copy.type = b->type; copy.length = plane.length; copy.bytesused = plane.bytesused;
        copy.m.offset = plane.m.mem_offset; *b = copy; return 0;
    }
    if (req == VIDIOC_REQBUFS) {
        auto* b = static_cast<v4l2_requestbuffers*>(arg); auto copy = *b;
        copy.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
        int ret = raw(fd,req,&copy); if (!ret) { copy.type = b->type; *b = copy; } return ret;
    }
    if (req == VIDIOC_STREAMON || req == VIDIOC_STREAMOFF) {
        auto type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE; return raw(fd,req,&type);
    }
    if (req == VIDIOC_G_PARM || req == VIDIOC_S_PARM) {
        auto* p = static_cast<v4l2_streamparm*>(arg); auto copy = *p;
        copy.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
        int ret = raw(fd,req,&copy); if (!ret) { copy.type = p->type; *p = copy; } return ret;
    }
    return raw(fd,req,arg);
}
int control(int fd, unsigned long req, void* arg) {
    Node node;
    if (!nodeForFd(fd,&node) || !identityMatches(fd,node)) return fail(EACCES);
    if ((req==VIDIOC_S_FMT || req==VIDIOC_STREAMON) && !sourceGraphMatches(fd,node)) return fail(EACCES);
    v4l2_capability cap{}; if (!capForFd(fd,&cap)) return fail(ENODEV);
    const auto bits = cap.capabilities & V4L2_CAP_DEVICE_CAPS ? cap.device_caps : cap.capabilities;
    if (req == VIDIOC_DQBUF) {
        pollfd p{fd,POLLIN,0}; int n = poll(&p,1,2000);
        if (n < 0) return -1;
        if (!n) return fail(ETIMEDOUT);
        if (!(p.revents & POLLIN)) return fail(EIO);
    }
    return translateControl(fd,bits & V4L2_CAP_VIDEO_CAPTURE_MPLANE,req,arg,rawIoctl);
}
uint32_t stride(int fd) {
    v4l2_format format{}; format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    return control(fd,VIDIOC_G_FMT,&format) == 0 ? format.fmt.pix.bytesperline : 0;
}
std::vector<std::pair<int32_t,int32_t>> frameSizes(int fd, uint32_t pixel) {
    bool supported = false;
    for (uint32_t i=0;i<64;i++) {
        v4l2_fmtdesc f{}; f.index=i; f.type=V4L2_BUF_TYPE_VIDEO_CAPTURE;
        if (control(fd,VIDIOC_ENUM_FMT,&f)) break;
        if (f.pixelformat == pixel) supported = true;
    }
    if (!supported) return {};
    std::set<std::pair<int32_t,int32_t>> sizes;
    for (uint32_t i=0;i<256;i++) {
        v4l2_frmsizeenum s{}; s.index=i; s.pixel_format=pixel;
        if (control(fd,VIDIOC_ENUM_FRAMESIZES,&s)) break;
        if (s.type == V4L2_FRMSIZE_TYPE_DISCRETE) sizes.emplace(s.discrete.width,s.discrete.height);
        else {
            sizes.emplace(s.stepwise.min_width,s.stepwise.min_height);
            sizes.emplace(s.stepwise.max_width,s.stepwise.max_height);
            // Camera2's mandatory concurrent stream combinations require s720p;
            // include standard sizes only when the driver explicitly permits them.
            auto fits = [](uint32_t value,uint32_t low,uint32_t high,uint32_t step) {
                return value>=low && value<=high && step && (value-low)%step==0;
            };
            for (const auto& size : {std::pair<uint32_t,uint32_t>{1280,720},{640,480}}) {
                if (fits(size.first,s.stepwise.min_width,s.stepwise.max_width,s.stepwise.step_width) &&
                    fits(size.second,s.stepwise.min_height,s.stepwise.max_height,s.stepwise.step_height))
                    sizes.emplace(size.first,size.second);
            }
            break;
        }
    }
    v4l2_format current{}; current.type=V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (!control(fd,VIDIOC_G_FMT,&current) && current.fmt.pix.pixelformat==pixel)
        sizes.emplace(current.fmt.pix.width,current.fmt.pix.height);
    return {sizes.begin(),sizes.end()};
}
std::vector<v4l2_fract> frameIntervals(int fd, uint32_t pixel, uint32_t w, uint32_t h) {
    Node node; if (!nodeForFd(fd,&node)) return {};
    if (!node.timingNode.empty()) {
        const auto rate=signalInterval(node);
        return rate.numerator && rate.denominator ? std::vector<v4l2_fract>{rate} : std::vector<v4l2_fract>{};
    }
    std::vector<v4l2_fract> rates;
    for (uint32_t i=0;i<256;i++) {
        v4l2_frmivalenum r{}; r.index=i; r.pixel_format=pixel; r.width=w; r.height=h;
        if (control(fd,VIDIOC_ENUM_FRAMEINTERVALS,&r)) break;
        if (r.type==V4L2_FRMIVAL_TYPE_DISCRETE) rates.push_back(r.discrete);
        else { rates.push_back(r.stepwise.min); rates.push_back(r.stepwise.max); break; }
    }
    return rates;
}
}
