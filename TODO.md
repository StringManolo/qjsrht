# TODO - Future Improvements

## High Priority

- [x] Basic QuickJS HTTP server
- [x] Express.js-like framework
- [x] Auto-start on boot
- [x] Tor hidden service support
- [x] Debug/Production modes
- [x] GitHub Actions build workflow
- [ ] Add real Tor binary compilation (currently using placeholders)
- [ ] Test on actual Android devices (API 21-28)
- [ ] Add app icons (currently using defaults)

## Medium Priority

- [ ] Add proper error handling for network failures
- [ ] Implement request rate limiting
- [ ] Add static file serving support
- [ ] Create WebSocket support
- [ ] Add HTTPS/TLS via mbedtls
- [ ] Implement connection pooling
- [ ] Add better logging system with rotation
- [ ] Create admin dashboard (accessible via browser)

## Low Priority

- [ ] IPv6 support
- [ ] Add database integration examples (SQLite)
- [ ] Create Docker examples for testing
- [ ] Add benchmarking suite
- [ ] API 29+ support via NDK
- [ ] Add TypeScript definitions for express.js
- [ ] Create comprehensive test suite
- [ ] Multi-threading support (worker threads)

## Documentation

- [x] Basic README
- [x] Usage examples
- [ ] Video tutorial
- [ ] Troubleshooting guide expansion
- [ ] Performance tuning guide
- [ ] Security best practices guide
- [ ] API reference documentation
- [ ] Contribution guidelines

## Known Issues

- Tor binaries in workflow are placeholders (need actual cross-compilation)
- No app icons included (uses Android defaults)
- No chunked transfer encoding support
- Binary data handling is string-based (needs Uint8Array)
- Single-threaded (no parallel request processing)
- No request body size limits
- No connection timeout configuration

## Community Requests

*Add community feature requests here*

## Notes

- Android API 28 is the highest we can target for direct binary execution
- QuickJS has no built-in async/await support - keep this in mind for complex workflows
- Express.js framework is synchronous - blocking operations will block all requests
- Memory usage is excellent but grows with concurrent connections
