import SwiftUI

/// First-launch access password (lock/README.md). Only active when the app carries access-lock.json
/// (a salt and a PBKDF2-SHA256 hash, never the password). Once the right password is typed this device
/// stays unlocked; the saved value is the lock's hash, so a new password locks it again.
/// To remove: delete lock/access-lock.json (the gate then shows the app straight away), or drop this file
/// and the AccessGate wrapper in AppDelegate.swift.
enum AccessLock {
    private static let key = "access_unlocked"

    static let lock: [String: Any]? = {
        guard let url = Bundle.main.url(forResource: "access-lock", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let hash = j["hash"] as? String, !hash.isEmpty else { return nil }
        return j
    }()

    static var unlocked: Bool {
        guard let lock, let hash = lock["hash"] as? String else { return true }
        return UserDefaults.standard.string(forKey: key) == hash
    }

    static func check(_ typed: String) -> Bool {
        guard let lock, let hash = lock["hash"] as? String, let want = Data(base64Encoded: hash),
              let saltText = lock["salt"] as? String, let salt = Data(base64Encoded: saltText) else { return true }
        let iter = (lock["iter"] as? Int) ?? 300_000
        guard let got = VaultCrypto.deriveKey(typed, salt: salt, iterations: iter), got == want else { return false }
        UserDefaults.standard.set(hash, forKey: key)
        return true
    }
}

struct AccessGate<Content: View>: View {
    @ViewBuilder var content: () -> Content
    @State private var open = AccessLock.unlocked
    @State private var typed = ""
    @State private var checking = false
    @State private var wrong = false
    @FocusState private var focused: Bool

    var body: some View {
        if open {
            content()
        } else {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 0) {
                    Text("Enter the access password")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                    Text("You only need to do this once on this device.")
                        .font(.system(size: 15))
                        .foregroundColor(Theme.dim)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)
                        .padding(.bottom, 24)
                    SecureField("Password", text: $typed)
                        .focused($focused)
                        .submitLabel(.go)
                        .onSubmit(check)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 17))
                        .foregroundColor(.white)
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x1C1C1E)))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(hex: 0x38383A), lineWidth: 1))
                    Button(action: check) {
                        Text(checking ? "Checking…" : "Unlock")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.red))
                    }
                    .padding(.top, 16)
                    .disabled(checking)
                    Text(wrong ? "That password isn't right." : " ")
                        .font(.system(size: 15))
                        .foregroundColor(Theme.redSoft)
                        .padding(.top, 16)
                }
                .frame(maxWidth: 340)
                .padding(24)
            }
            .preferredColorScheme(.dark)
            .onAppear { focused = true }
        }
    }

    private func check() {
        let text = typed
        guard !checking, !VaultCrypto.normalise(text).isEmpty else { return }
        checking = true
        wrong = false
        DispatchQueue.global(qos: .userInitiated).async {
            let ok = AccessLock.check(text)
            DispatchQueue.main.async {
                checking = false
                if ok { open = true } else { wrong = true; typed = "" }
            }
        }
    }
}
