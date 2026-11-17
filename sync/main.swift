// Checks the iPhone's sync crypto against sync/vector.json (run in CI on macOS):
//   swiftc ios/Codio/Store/VaultCrypto.swift sync/main.swift -o /tmp/vault-check && /tmp/vault-check sync/vector.json
import Foundation

let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "sync/vector.json"
let v = try! JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
let box = v["box"] as! [String: Any]
let key = VaultCrypto.deriveKey(v["passcode"] as! String, salt: Data(base64Encoded: box["salt"] as! String)!, iterations: box["iter"] as! Int)!
let hex = key.map { String(format: "%02x", $0) }.joined()
guard hex == v["keyHex"] as! String else { print("key mismatch: \(hex)"); exit(1) }
guard let items = VaultCrypto.open(key, box), (items["tmdbKey"] as? [String: Any])?["value"] as? String == "0123456789abcdef0123456789abcdef" else {
    print("could not open the vector box"); exit(1)
}
let again = try! VaultCrypto.seal(key, salt: box["salt"] as! String, iter: 600000, items: items)
guard VaultCrypto.open(key, again) != nil, VaultCrypto.open(Data(repeating: 1, count: 32), again) == nil else { print("round trip failed"); exit(1) }
print("sync crypto OK")
