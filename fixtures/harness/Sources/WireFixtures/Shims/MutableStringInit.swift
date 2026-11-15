// shim: Darwin Foundation's NSMutableString() initializer; swift-corelibs-foundation only has init(capacity:).
#if !canImport(Darwin)
import Foundation

extension NSMutableString {
    // A defaulted Void parameter lets `NSMutableString()` resolve here without
    // overriding NSString's init(), which an extension may not do.
    convenience init(_ empty: Void = ()) {
        self.init(capacity: 0)
    }
}
#endif
