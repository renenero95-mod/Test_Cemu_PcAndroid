#pragma once
struct ActiveSettings { static fs::path GetUserDataPath(std::string_view p) { return fs::path("/tmp/mock_userdata") / std::string(p); } };
