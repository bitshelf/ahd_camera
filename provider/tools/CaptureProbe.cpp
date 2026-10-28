// SPDX-License-Identifier: Apache-2.0
// Standalone diagnostic: access is limited by the same board profile as the Provider.
#include "CaptureIo.h"
#include <sys/mman.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>
using namespace ahd::capture;
int probe(const Node& node, bool capture) {
    const int fd=openNode(node.path);
    if (fd<0) { std::fprintf(stderr,"%s: open: %s\n",node.id.c_str(),strerror(errno));return 1; }
    v4l2_format fmt{};fmt.type=V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (control(fd,VIDIOC_G_FMT,&fmt)) { close(fd);return 1; }
    const auto rates=frameIntervals(fd,V4L2_PIX_FMT_NV12,fmt.fmt.pix.width,fmt.fmt.pix.height);
    std::printf("%s %ux%u stride=%u size=%u rates=",node.id.c_str(),fmt.fmt.pix.width,fmt.fmt.pix.height,fmt.fmt.pix.bytesperline,fmt.fmt.pix.sizeimage);
    for (const auto& r:rates) std::printf("%u/%u ",r.denominator,r.numerator);
    std::printf("\n");
    if (!capture) { close(fd);return rates.empty(); }
    int result=1;
    v4l2_requestbuffers req{};req.type=V4L2_BUF_TYPE_VIDEO_CAPTURE;req.memory=V4L2_MEMORY_MMAP;req.count=4;
    auto kind=V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (!control(fd,VIDIOC_S_FMT,&fmt) && !control(fd,VIDIOC_REQBUFS,&req)) {
        bool queued=req.count>=2 && req.count<=16;
        for (unsigned i=0;i<req.count && queued;i++) {
            v4l2_buffer b{};b.type=kind;b.memory=V4L2_MEMORY_MMAP;b.index=i;
            queued=!control(fd,VIDIOC_QUERYBUF,&b) && !control(fd,VIDIOC_QBUF,&b);
        }
        if (queued && !control(fd,VIDIOC_STREAMON,&kind)) {
            int frames=0;
            for (;frames<12;frames++) {
                v4l2_buffer b{};b.type=kind;b.memory=V4L2_MEMORY_MMAP;
                if (control(fd,VIDIOC_DQBUF,&b)) break;
                void* pixels=mmap(nullptr,b.bytesused,PROT_READ,MAP_SHARED,fd,b.m.offset);
                if (pixels==MAP_FAILED) break;
                volatile auto first=static_cast<unsigned char*>(pixels)[0];(void)first;
                munmap(pixels,b.bytesused);
                std::printf("%s frame=%d sequence=%u bytes=%u timestamp=%lld.%06ld\n",node.id.c_str(),frames,b.sequence,b.bytesused,static_cast<long long>(b.timestamp.tv_sec),static_cast<long>(b.timestamp.tv_usec));
                if (control(fd,VIDIOC_QBUF,&b)) break;
            }
            result=frames==12 ? 0 : 1;
        }
    }
    if (result) std::fprintf(stderr,"%s capture failed: %s\n",node.id.c_str(),strerror(errno));
    control(fd,VIDIOC_STREAMOFF,&kind);req.count=0;control(fd,VIDIOC_REQBUFS,&req);
    close(fd);return result;
}
int main(int argc,char** argv) {
    if (argc<2 || argc>3) { std::fprintf(stderr,"usage: ahd-capture-probe profile.xml [--capture]\n");return 2; }
    const auto config=loadConfig(argv[1]);setNodes(config.nodes);
    if (config.nodes.empty()) return 2;
    const bool capture=argc==3 && std::strcmp(argv[2],"--capture")==0;
    std::vector<int> results(config.nodes.size(),1);std::vector<std::thread> workers;
    for (size_t i=0;i<config.nodes.size();i++) workers.emplace_back([&,i]{results[i]=probe(config.nodes[i],capture);});
    for (auto& t:workers)t.join();
    for (auto r:results) if(r)return 1;
    return 0;
}
