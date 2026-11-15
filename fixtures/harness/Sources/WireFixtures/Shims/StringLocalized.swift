// shim: Darwin Foundation's String(localized:defaultValue:comment:); returns the default value, as Darwin does with no strings table.
#if !canImport(Darwin)
import Foundation

extension String {
    init(localized key: StaticString, defaultValue: String, comment: StaticString? = nil) {
        self = defaultValue
    }
}
#endif
