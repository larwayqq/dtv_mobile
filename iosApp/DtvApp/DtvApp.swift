import SwiftUI
import shared

@main
struct DtvApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeRootView()
        }
    }
}

struct ComposeRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
