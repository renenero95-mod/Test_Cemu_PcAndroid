// Stessa espressione del fix F3 in VulkanAPI.cpp (get_custom_driver_lib_name)
#include <filesystem>
#include <algorithm>
#include <string>
#include <cstdio>
namespace fs = std::filesystem;
static bool rejected(const std::string& lib_name) {
	const fs::path lib_path(lib_name);
	return lib_name.empty() || lib_path.is_absolute() || std::any_of(lib_path.begin(), lib_path.end(), [](const fs::path& part) { return part == ".."; });
}
int main() {
	struct { const char* n; bool atteso; } t[] = {
		{"vulkan.ad0735.so", false}, {"libvulkan_freedreno.so", false}, {"lib/arm64/vk.so", false}, {"foo..bar.so", false}, {".hidden.so", false},
		{"../x.so", true}, {"a/../b.so", true}, {"..", true}, {"/system/lib64/libc.so", true}, {"a/b/../../../c.so", true}, {"", true}, {"..\\x.so", false /* su Linux e' un nome letterale */}};
	int ko = 0;
	for (auto& c : t) { bool r = rejected(c.n); printf("%-26s rifiutato=%d atteso=%d %s\n", c.n, r, c.atteso, r == c.atteso ? "OK" : "<<< KO"); ko += (r != c.atteso); }
	printf("falliti: %d\n", ko); return ko;
}
