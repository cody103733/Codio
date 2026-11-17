import Foundation
import CryptoKit
import CommonCrypto

/// The sync passcode's crypto, on its own so CI can check it against sync/vector.json (see SYNC.md):
/// PBKDF2-HMAC-SHA256 (32-byte key) from the trimmed, NFC passcode, then AES-256-GCM with a 12-byte nonce,
/// the 16-byte tag after the ciphertext.
enum VaultCrypto {
    static func normalise(_ p: String) -> String {
        p.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping
    }

    static func deriveKey(_ passcode: String, salt: Data, iterations: Int) -> Data? {
        let pass = Array(normalise(passcode).utf8)
        var out = [UInt8](repeating: 0, count: 32)
        let status = salt.withUnsafeBytes { s -> Int32 in
            pass.withUnsafeBufferPointer { p in
                p.baseAddress!.withMemoryRebound(to: Int8.self, capacity: pass.count) { pp in
                    CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), pp, pass.count,
                                         s.bindMemory(to: UInt8.self).baseAddress, salt.count,
                                         CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(iterations), &out, 32)
                }
            }
        }
        return Int(status) == kCCSuccess ? Data(out) : nil
    }

    static func seal(_ key: Data, salt: String, iter: Int, items: [String: Any]) throws -> [String: Any] {
        let plain = try JSONSerialization.data(withJSONObject: ["v": 1, "items": items])
        let box = try AES.GCM.seal(plain, using: SymmetricKey(data: key))
        let data = box.ciphertext + box.tag
        return ["v": 1, "kdf": "pbkdf2-sha256", "iter": iter, "salt": salt,
                "iv": Data(box.nonce).base64EncodedString(), "data": data.base64EncodedString()]
    }

    /// The items in the box, or nil when this key doesn't open it.
    static func open(_ key: Data, _ box: [String: Any]) -> [String: Any]? {
        guard let ivText = box["iv"] as? String, let iv = Data(base64Encoded: ivText),
              let dataText = box["data"] as? String, let data = Data(base64Encoded: dataText), data.count >= 16,
              let nonce = try? AES.GCM.Nonce(data: iv),
              let sealed = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: data.prefix(data.count - 16), tag: data.suffix(16)),
              let plain = try? AES.GCM.open(sealed, using: SymmetricKey(data: key)),
              let o = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any] else { return nil }
        return o["items"] as? [String: Any] ?? [:]
    }
}
