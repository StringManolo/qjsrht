# Contributing to qjsrht

Thank you for considering contributing to qjsrht! This document provides guidelines for contributing to the project.

## How to Contribute

### Reporting Bugs

If you find a bug, please open an issue with:

1. **Description**: Clear description of the bug
2. **Steps to Reproduce**: Minimal steps to reproduce the issue
3. **Expected Behavior**: What you expected to happen
4. **Actual Behavior**: What actually happened
5. **Environment**: 
   - Android version
   - Device model
   - Build configuration (debug/production, onion enabled, etc.)
6. **Logs**: Relevant log output (if available)

### Suggesting Features

Feature requests are welcome! Please include:

1. **Use Case**: Why is this feature needed?
2. **Proposed Solution**: How you envision it working
3. **Alternatives**: Other solutions you've considered
4. **Additional Context**: Any other relevant information

### Pull Requests

1. **Fork the repository**
2. **Create a feature branch**: `git checkout -b feature/amazing-feature`
3. **Make your changes**
4. **Test your changes**: Ensure the APK builds and works
5. **Commit**: Use clear commit messages
6. **Push**: `git push origin feature/amazing-feature`
7. **Open a Pull Request**

#### PR Guidelines

- Keep PRs focused on a single feature or fix
- Update documentation if needed
- Test on actual Android devices when possible
- Follow existing code style
- Add examples if adding new features

## Development Setup

### Local Testing

While GitHub Actions handles the build, you can test locally:

```bash
# Clone the repo
git clone https://github.com/stringmanolo/qjsrht.git
cd qjsrht

# Modify config.json
nano config.json

# Edit server.js
vi app/src/main/assets/js/server.js

# Build (requires Android SDK)
./gradlew assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Testing Server Logic

You can test the express.js framework logic without building an APK:

```bash
# Install QuickJS (Linux)
sudo apt-get install quickjs

# Test server.js
cd app/src/main/assets/js
qjs --std server.js
```

Note: This won't include the native sockets module, but you can test basic logic.

## Code Style

### Kotlin

- Use 4 spaces for indentation
- Follow Kotlin coding conventions
- Use meaningful variable names
- Add comments for complex logic

### JavaScript

- Use 2 spaces for indentation
- Use ES6+ features (QuickJS supports ES2020)
- Prefer `const` over `let`, avoid `var`
- Use arrow functions where appropriate
- Add JSDoc comments for functions

### Example:

```javascript
/**
 * Validates user data
 * @param {Object} data - User data object
 * @returns {Object} Validation result with valid flag and error message
 */
function validateUser(data) {
  if (!data.name) {
    return { valid: false, error: 'Name required' };
  }
  return { valid: true };
}
```

## Areas for Contribution

### High Impact

- **Tor Binary Compilation**: Replace placeholder Tor binaries with actual cross-compiled binaries
- **Testing**: Test on various Android devices (API 21-28)
- **Documentation**: Improve setup guides, add videos
- **Performance**: Optimize express.js framework

### Medium Impact

- **Features**: Add WebSocket support, static file serving
- **Examples**: Create more real-world usage examples
- **Security**: Add rate limiting, request validation
- **Icons**: Design proper app icons

### Low Impact

- **Code Quality**: Refactor for better maintainability
- **Logging**: Improve logging system
- **UI**: Enhance debug mode UI
- **CI/CD**: Improve GitHub Actions workflow

## Testing Checklist

Before submitting a PR, ensure:

- [ ] APK builds successfully via GitHub Actions
- [ ] App installs on Android device
- [ ] Server starts and responds to requests
- [ ] Boot auto-start works (if applicable)
- [ ] Tor integration works (if enabled)
- [ ] Debug/Production modes both work
- [ ] No crashes or ANRs
- [ ] Documentation updated

## Questions?

- Open an issue for questions
- Join discussions in existing issues
- Contact [@StringManolo](https://github.com/StringManolo)

---

Thank you for making qjsrht better! 🚀
