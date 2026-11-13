# Existing distributed inference projects reviewed (2026-11-14)

- https://github.com/alex8819/DistAi — Android app and PC tools using llama.cpp RPC.
  Its documented Android builds target ARM64 (v8/v82/v86). Reusing the APK directly
  does not cover the user's measured 32-bit Android process. ARM32 portability is unverified.
- https://github.com/dzaczek/phoneborg — experimental phone cluster with a Linux/macOS
  controller and provisioning over ADB/USB. Useful reference for routing, pools and RPC.
  Compatibility with the user's 32-bit device has not been established.
- https://github.com/OpenCPIL/prima.cpp — heterogeneous distributed inference;
  README describes Android via Termux. Ready-to-install support for this user's phone is unverified.
- https://github.com/ggml-org/llama.cpp — engine already embedded in our application.

No code from the first three repositories has been incorporated. Before reuse,
inspect the relevant implementation, licenses, exact dependency revision and ARM32 support.
Our v0.3 measures transport conditions first; it does not implement model partitioning.
Prefer an existing compatible distributed backend over inventing an inference protocol.
