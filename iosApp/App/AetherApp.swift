import Foundation
import SwiftUI
import UIKit
import AetherShared
import FileProvider

extension Notification.Name {
    static let openAlpineFileManager = Notification.Name("Aether.OpenAlpineFileManager")
}

private final class AetherAppDelegate: NSObject, UIApplicationDelegate {
    private let internetPermissionRequester = AetherInternetPermissionRequester()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        AetherRuntimeHost.shared.registerBackgroundExecution()
        let domain = NSFileProviderDomain(identifier: NSFileProviderDomainIdentifier("com.kira.ditto"), displayName: "Aether")
        NSFileProviderManager.add(domain) { error in
            if let error = error as NSError? {
                NSLog(
                    "Aether File Provider registration failed (%@ %ld): %@",
                    error.domain,
                    error.code,
                    error.localizedDescription
                )
            } else {
                NSLog("Aether File Provider domain registered")
            }
        }
        AetherRuntimeHost.shared.refreshApkRepositoriesForCurrentNetwork()
        internetPermissionRequester.requestAccess()
        return true
    }
}

/// iOS does not expose an explicit API for the Wireless LAN & Cellular Data prompt.
/// Starting a real internet request on first launch lets the system present it before
/// onboarding needs provider access. The system remembers the choice per installation.
final class AetherInternetPermissionRequester {
    private static let requestRecordedKey = "internetPermissionRequestRecorded"
    private var session: URLSession?

    func requestAccess() {
        guard
            session == nil,
            !UserDefaults.standard.bool(forKey: Self.requestRecordedKey)
        else { return }
        UserDefaults.standard.set(true, forKey: Self.requestRecordedKey)

        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        let session = URLSession(configuration: configuration)
        self.session = session
        session.dataTask(with: makeInternetPermissionRequest()) { [weak self] _, _, _ in
            session.finishTasksAndInvalidate()
            self?.session = nil
        }.resume()
    }
}

func makeInternetPermissionRequest() -> URLRequest {
    var request = URLRequest(
        url: URL(string: "https://models.dev/catalog.json")!,
        cachePolicy: .reloadIgnoringLocalCacheData,
        timeoutInterval: 5
    )
    request.httpMethod = "HEAD"
    return request
}

@main
struct AetherIOSApp: App {
    @UIApplicationDelegateAdaptor(AetherAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var presentsAlpineFileManager = false
    @StateObject private var nativeSettings = NativeSettingsModel()

    var body: some Scene {
        WindowGroup {
            ComposeRootView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .ignoresSafeArea()
                .onReceive(NotificationCenter.default.publisher(for: .openAlpineFileManager)) { _ in
                    presentsAlpineFileManager = true
                }
                .sheet(isPresented: $presentsAlpineFileManager) {
                    AlpineFileManagerView(host: AetherRuntimeHost.shared)
                }
                .sheet(isPresented: $nativeSettings.isPresented) {
                    NativeSettingsView(model: nativeSettings)
                }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                SharedApplicationLifecycle.shared.enterBackground()
            case .active:
                SharedApplicationLifecycle.shared.enterForeground()
            default:
                break
            }
        }
    }
}

private struct ComposeRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        FullscreenComposeViewController(
            content: MainViewControllerKt.MainViewController(runtimeHost: AetherRuntimeHost.shared)
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private final class FullscreenComposeViewController: UIViewController {
    private let content: UIViewController

    init(content: UIViewController) {
        self.content = content
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear

        addChild(content)
        content.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content.view)
        NSLayoutConstraint.activate([
            content.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            content.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            content.view.topAnchor.constraint(equalTo: view.topAnchor),
            content.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        content.didMove(toParent: self)
    }
}
