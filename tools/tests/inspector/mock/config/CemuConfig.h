#pragma once
struct MockConfig { bool inspector_enabled = true; };
inline MockConfig& GetConfig() { static MockConfig c; return c; }
