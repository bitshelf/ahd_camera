// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <linux/videodev2.h>
#include <linux/media.h>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace ahd::capture {
struct Node {
    std::string id, path, driver, card, bus, timingNode, timingName;
    uint32_t timingPad = 0;
    std::string mediaNode;
};
struct Config {
    std::vector<Node> nodes;
    uint32_t maxConcurrent = 1;
    uint64_t maxPixelRate = 0;
    int32_t resourceCost = 100;
};
struct Graph {
    std::vector<media_v2_interface> interfaces;
    std::vector<media_v2_pad> pads;
    std::vector<media_v2_link> links;
};
bool graphConnects(const Graph& graph, uint32_t videoMajor, uint32_t videoMinor,
                   uint32_t decoderMajor, uint32_t decoderMinor);
Config loadConfig(const char* path);
void setNodes(const std::vector<Node>& nodes);
int openNode(const std::string& path);
bool identityMatches(int fd, const Node& node);
int control(int fd, unsigned long request, void* arg);
uint32_t stride(int fd);
std::vector<std::pair<int32_t, int32_t>> frameSizes(int fd, uint32_t pixel);
std::vector<v4l2_fract> frameIntervals(int fd, uint32_t pixel, uint32_t w, uint32_t h);
// The compatibility boundary translates contiguous single-memory-plane MPLANE
// buffers only. Separate Y/UV memory planes are rejected, never misinterpreted.
using Ioctl = int (*)(int, unsigned long, void*);
int translateControl(int fd, bool multiplane, unsigned long request, void* arg, Ioctl raw);
}
