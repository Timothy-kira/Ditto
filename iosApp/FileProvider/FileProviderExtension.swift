import FileProvider
import UniformTypeIdentifiers

private enum AetherFileProviderStorage {
    static let appGroupIdentifier = "group.com.kira.ditto"

    static let root: URL = {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupIdentifier
        ) else {
            fatalError("Aether File Provider cannot access its App Group container.")
        }
        let root = container.appendingPathComponent("AetherAlpine/data", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root.standardizedFileURL
    }()

    static func url(for identifier: NSFileProviderItemIdentifier) throws -> URL {
        if identifier == .rootContainer || identifier == .workingSet {
            return root
        }
        let relativePath = identifier.rawValue
        guard !relativePath.isEmpty, !relativePath.hasPrefix("/") else {
            throw NSFileProviderError(.noSuchItem)
        }
        let candidate = root.appendingPathComponent(relativePath).standardizedFileURL
        guard candidate.path.hasPrefix(root.path + "/") else {
            throw NSFileProviderError(.noSuchItem)
        }
        return candidate
    }

    static func identifier(for url: URL) -> NSFileProviderItemIdentifier {
        let standardized = url.standardizedFileURL
        if standardized.path == root.path {
            return .rootContainer
        }
        let prefix = root.path + "/"
        return NSFileProviderItemIdentifier(String(standardized.path.dropFirst(prefix.count)))
    }

    static func parentIdentifier(for url: URL) -> NSFileProviderItemIdentifier {
        let parent = url.deletingLastPathComponent().standardizedFileURL
        return parent.path == root.path ? .rootContainer : identifier(for: parent)
    }
}

final class FileProviderExtension: NSObject, NSFileProviderReplicatedExtension {
    required init(domain: NSFileProviderDomain) {
        super.init()
    }

    func invalidate() {}

    func item(
        for identifier: NSFileProviderItemIdentifier,
        request: NSFileProviderRequest,
        completionHandler: @escaping (NSFileProviderItem?, Error?) -> Void
    ) -> Progress {
        do {
            let url = try AetherFileProviderStorage.url(for: identifier)
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw NSFileProviderError(.noSuchItem)
            }
            completionHandler(FileProviderItem(url: url), nil)
        } catch {
            completionHandler(nil, error)
        }
        return Progress(totalUnitCount: 1)
    }

    func fetchContents(
        for itemIdentifier: NSFileProviderItemIdentifier,
        version requestedVersion: NSFileProviderItemVersion?,
        request: NSFileProviderRequest,
        completionHandler: @escaping (URL?, NSFileProviderItem?, Error?) -> Void
    ) -> Progress {
        do {
            let source = try AetherFileProviderStorage.url(for: itemIdentifier)
            guard FileManager.default.fileExists(atPath: source.path) else {
                throw NSFileProviderError(.noSuchItem)
            }
            let temporary = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString, isDirectory: false)
            try FileManager.default.copyItem(at: source, to: temporary)
            completionHandler(temporary, FileProviderItem(url: source), nil)
        } catch {
            completionHandler(nil, nil, error)
        }
        return Progress(totalUnitCount: 1)
    }

    func createItem(
        basedOn itemTemplate: NSFileProviderItem,
        fields: NSFileProviderItemFields,
        contents url: URL?,
        options: NSFileProviderCreateItemOptions = [],
        request: NSFileProviderRequest,
        completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void
    ) -> Progress {
        do {
            let parent = try AetherFileProviderStorage.url(for: itemTemplate.parentItemIdentifier)
            let destination = parent.appendingPathComponent(itemTemplate.filename)
            guard !FileManager.default.fileExists(atPath: destination.path) else {
                throw CocoaError(.fileWriteFileExists)
            }
            if itemTemplate.contentType?.conforms(to: .folder) == true {
                try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: false)
            } else if let url {
                try FileManager.default.copyItem(at: url, to: destination)
            } else {
                try Data().write(to: destination, options: .atomic)
            }
            completionHandler(FileProviderItem(url: destination), [], false, nil)
        } catch {
            completionHandler(nil, fields, false, error)
        }
        return Progress(totalUnitCount: 1)
    }

    func modifyItem(
        _ item: NSFileProviderItem,
        baseVersion version: NSFileProviderItemVersion,
        changedFields: NSFileProviderItemFields,
        contents newContents: URL?,
        options: NSFileProviderModifyItemOptions = [],
        request: NSFileProviderRequest,
        completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void
    ) -> Progress {
        do {
            let source = try AetherFileProviderStorage.url(for: item.itemIdentifier)
            guard FileManager.default.fileExists(atPath: source.path) else {
                throw NSFileProviderError(.noSuchItem)
            }
            let parent = try AetherFileProviderStorage.url(for: item.parentItemIdentifier)
            let destination = parent.appendingPathComponent(item.filename)
            var current = source
            if source.standardizedFileURL != destination.standardizedFileURL {
                guard !FileManager.default.fileExists(atPath: destination.path) else {
                    throw CocoaError(.fileWriteFileExists)
                }
                try FileManager.default.moveItem(at: source, to: destination)
                current = destination
            }
            if let newContents, item.contentType?.conforms(to: .folder) != true {
                let staged = FileManager.default.temporaryDirectory
                    .appendingPathComponent(UUID().uuidString, isDirectory: false)
                try FileManager.default.copyItem(at: newContents, to: staged)
                _ = try FileManager.default.replaceItemAt(current, withItemAt: staged)
            }
            completionHandler(FileProviderItem(url: current), [], false, nil)
        } catch {
            completionHandler(nil, changedFields, false, error)
        }
        return Progress(totalUnitCount: 1)
    }

    func deleteItem(
        identifier: NSFileProviderItemIdentifier,
        baseVersion version: NSFileProviderItemVersion,
        options: NSFileProviderDeleteItemOptions = [],
        request: NSFileProviderRequest,
        completionHandler: @escaping (Error?) -> Void
    ) -> Progress {
        do {
            let url = try AetherFileProviderStorage.url(for: identifier)
            guard identifier != .rootContainer else {
                throw CocoaError(.fileWriteNoPermission)
            }
            if FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
            }
            completionHandler(nil)
        } catch {
            completionHandler(error)
        }
        return Progress(totalUnitCount: 1)
    }

    func enumerator(
        for containerItemIdentifier: NSFileProviderItemIdentifier,
        request: NSFileProviderRequest
    ) throws -> NSFileProviderEnumerator {
        FileProviderEnumerator(identifier: containerItemIdentifier)
    }
}

final class FileProviderEnumerator: NSObject, NSFileProviderEnumerator {
    private let identifier: NSFileProviderItemIdentifier

    init(identifier: NSFileProviderItemIdentifier) {
        self.identifier = identifier
        super.init()
    }

    func enumerateItems(
        for observer: NSFileProviderEnumerationObserver,
        startingAt page: NSFileProviderPage
    ) {
        do {
            let base = try AetherFileProviderStorage.url(for: identifier)
            let urls = try FileManager.default.contentsOfDirectory(
                at: base,
                includingPropertiesForKeys: [.contentTypeKey, .contentModificationDateKey, .fileSizeKey],
                options: [.skipsHiddenFiles]
            )
            observer.didEnumerate(urls.map(FileProviderItem.init))
            observer.finishEnumerating(upTo: nil)
        } catch {
            observer.finishEnumeratingWithError(error)
        }
    }

    func enumerateChanges(
        for observer: NSFileProviderChangeObserver,
        from anchor: NSFileProviderSyncAnchor
    ) {
        observer.finishEnumeratingChanges(upTo: Self.syncAnchor(), moreComing: false)
    }

    func currentSyncAnchor(completionHandler: @escaping (NSFileProviderSyncAnchor?) -> Void) {
        completionHandler(Self.syncAnchor())
    }

    func invalidate() {}

    private static func syncAnchor() -> NSFileProviderSyncAnchor {
        NSFileProviderSyncAnchor(Data("aether-local-v1".utf8))
    }
}

final class FileProviderItem: NSObject, NSFileProviderItem {
    private let url: URL
    private let values: URLResourceValues

    init(url: URL) {
        self.url = url.standardizedFileURL
        values = (try? url.resourceValues(forKeys: [
            .contentTypeKey,
            .contentModificationDateKey,
            .creationDateKey,
            .fileSizeKey,
            .isDirectoryKey,
        ])) ?? URLResourceValues()
        super.init()
    }

    var itemIdentifier: NSFileProviderItemIdentifier {
        AetherFileProviderStorage.identifier(for: url)
    }

    var parentItemIdentifier: NSFileProviderItemIdentifier {
        itemIdentifier == .rootContainer
            ? .rootContainer
            : AetherFileProviderStorage.parentIdentifier(for: url)
    }

    var filename: String {
        itemIdentifier == .rootContainer ? "Aether" : url.lastPathComponent
    }

    var contentType: UTType {
        values.contentType ?? (values.isDirectory == true ? .folder : .data)
    }

    var capabilities: NSFileProviderItemCapabilities {
        if itemIdentifier == .rootContainer {
            return [.allowsReading, .allowsWriting]
        }
        return [
            .allowsReading,
            .allowsWriting,
            .allowsRenaming,
            .allowsReparenting,
            .allowsTrashing,
            .allowsDeleting,
        ]
    }

    var itemVersion: NSFileProviderItemVersion {
        let timestamp = values.contentModificationDate?.timeIntervalSince1970 ?? 0
        let size = values.fileSize ?? 0
        let version = Data("\(timestamp):\(size)".utf8)
        return NSFileProviderItemVersion(contentVersion: version, metadataVersion: version)
    }

    var documentSize: NSNumber? {
        values.fileSize.map(NSNumber.init)
    }

    var creationDate: Date? {
        values.creationDate
    }

    var contentModificationDate: Date? {
        values.contentModificationDate
    }
}
