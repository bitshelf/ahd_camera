// SPDX-License-Identifier: Apache-2.0
#include "CaptureIo.h"
#include <gtest/gtest.h>
#include <cerrno>
#include <fstream>
#include <unistd.h>
using namespace ahd::capture;
namespace {
int calls=0;
int fakeIoctl(int, unsigned long request, void* arg) {
    calls++;
    if (request==VIDIOC_G_FMT) {
        auto* f=static_cast<v4l2_format*>(arg);
        EXPECT_EQ(V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE,f->type);
        f->fmt.pix_mp.num_planes=1; f->fmt.pix_mp.width=1920; f->fmt.pix_mp.height=1080;
        f->fmt.pix_mp.pixelformat=V4L2_PIX_FMT_NV12;
        f->fmt.pix_mp.plane_fmt[0].bytesperline=2048;
        f->fmt.pix_mp.plane_fmt[0].sizeimage=3317760;
    } else if (request==VIDIOC_DQBUF) {
        auto* b=static_cast<v4l2_buffer*>(arg);
        EXPECT_EQ(1u,b->length);
        b->index=3; b->sequence=42; b->timestamp={123,456};
        b->m.planes[0].bytesused=3317760; b->m.planes[0].length=3317760;
        b->m.planes[0].m.mem_offset=4096;
    }
    return 0;
}
int twoPlanes(int, unsigned long, void* arg) {
    static_cast<v4l2_format*>(arg)->fmt.pix_mp.num_planes=2;return 0;
}
}
TEST(CaptureIo, RejectsUnlistedPathBeforeOpening) {
    setNodes({}); errno=0;
    EXPECT_EQ(-1,openNode("/dev/video0")); EXPECT_EQ(EACCES,errno);
    EXPECT_EQ(-1,openNode("/dev/video11/../video0")); EXPECT_EQ(EACCES,errno);
}
TEST(CaptureIo, TranslatesPaddedLayoutWithoutChangingItsStride) {
    v4l2_format f{};f.type=V4L2_BUF_TYPE_VIDEO_CAPTURE;
    ASSERT_EQ(0,translateControl(1,true,VIDIOC_G_FMT,&f,fakeIoctl));
    EXPECT_EQ(1920u,f.fmt.pix.width);EXPECT_EQ(2048u,f.fmt.pix.bytesperline);
    EXPECT_EQ(3317760u,f.fmt.pix.sizeimage); EXPECT_EQ(V4L2_BUF_TYPE_VIDEO_CAPTURE,f.type);
}
TEST(CaptureIo, KeepsDequeuedBufferIdentityTimestampAndOffset) {
    v4l2_buffer b{};b.type=V4L2_BUF_TYPE_VIDEO_CAPTURE;b.memory=V4L2_MEMORY_MMAP;
    ASSERT_EQ(0,translateControl(1,true,VIDIOC_DQBUF,&b,fakeIoctl));
    EXPECT_EQ(3u,b.index);EXPECT_EQ(42u,b.sequence);EXPECT_EQ(123,b.timestamp.tv_sec);
    EXPECT_EQ(3317760u,b.bytesused);EXPECT_EQ(4096u,b.m.offset);
}
TEST(CaptureIo, RejectsSeparateMemoryPlanes) {
    v4l2_format f{};f.type=V4L2_BUF_TYPE_VIDEO_CAPTURE;
    EXPECT_EQ(-1,translateControl(1,true,VIDIOC_G_FMT,&f,twoPlanes));EXPECT_EQ(ENOTSUP,errno);
}
TEST(CaptureIo, PassesSinglePlaneControlThroughOnce) {
    calls=0;int arg=0;
    ASSERT_EQ(0,translateControl(1,false,VIDIOC_STREAMON,&arg,fakeIoctl));EXPECT_EQ(1,calls);
}
TEST(CaptureIo, MalformedDuplicateConfigurationFailsClosed) {
    char path[]="/tmp/ahd-provider-config-XXXXXX";int fd=mkstemp(path);ASSERT_GE(fd,0);close(fd);
    {
        std::ofstream f(path);
        f<<"<AhdCameraProvider><Camera id='ahd-a' path='/dev/video1' driver='x' card='x' bus='x'/>"
           "<Camera id='ahd-a' path='/dev/video2' driver='x' card='x' bus='x'/></AhdCameraProvider>";
    }
    EXPECT_TRUE(loadConfig(path).nodes.empty());unlink(path);
}
namespace {
Graph exampleGraph(bool enabled) {
    Graph graph;
    media_v2_interface video{};video.id=10;video.devnode={81,11};
    media_v2_interface decoder{};decoder.id=11;decoder.devnode={81,80};
    graph.interfaces={video,decoder};
    media_v2_pad sink{};sink.id=20;sink.entity_id=1;
    media_v2_pad source{};source.id=21;source.entity_id=2;
    graph.pads={sink,source};
    media_v2_link vi{};vi.source_id=10;vi.sink_id=1;vi.flags=MEDIA_LNK_FL_INTERFACE_LINK;
    media_v2_link di{};di.source_id=11;di.sink_id=2;di.flags=MEDIA_LNK_FL_INTERFACE_LINK;
    media_v2_link data{};data.source_id=21;data.sink_id=20;data.flags=enabled ? MEDIA_LNK_FL_ENABLED : 0;
    graph.links={vi,di,data};return graph;
}
}
TEST(CaptureIo, RequiresEnabledRouteToTheConfiguredDecoder) {
    EXPECT_TRUE(graphConnects(exampleGraph(true),81,11,81,80));
    EXPECT_FALSE(graphConnects(exampleGraph(false),81,11,81,80));
    EXPECT_FALSE(graphConnects(exampleGraph(true),81,12,81,80));
    EXPECT_FALSE(graphConnects(exampleGraph(true),81,11,81,99));
}
TEST(CaptureIo, DoesNotAcceptTheReverseDirectionAsCameraOwnership) {
    auto graph=exampleGraph(true);
    const uint32_t source=graph.links.back().source_id;
    graph.links.back().source_id=graph.links.back().sink_id;
    graph.links.back().sink_id=source;
    EXPECT_FALSE(graphConnects(graph,81,11,81,80));
}
