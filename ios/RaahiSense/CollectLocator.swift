import CoreLocation
import Foundation
import RaahiSenseLogic

/// GPS stays off unless the developer turns it on for training photos.
final class CollectLocator: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let lock = NSLock()
    private var reading: GpsReading?
    var onDenied: (() -> Void)?
    private var wantsUpdates = false

    func currentReading() -> GpsReading? {
        lock.lock()
        defer { lock.unlock() }
        return reading
    }

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 25
    }

    func setActive(_ active: Bool) {
        wantsUpdates = active
        if !active {
            manager.stopUpdatingLocation()
            return
        }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.startUpdatingLocation()
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        default:
            wantsUpdates = false
            onDenied?()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard wantsUpdates else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.startUpdatingLocation()
        case .notDetermined:
            break
        default:
            wantsUpdates = false
            onDenied?()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let millis = Int64((location.timestamp.timeIntervalSince1970 * 1000).rounded())
        let next = GpsReading(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            accuracyMeters: location.horizontalAccuracy >= 0 ? Float(location.horizontalAccuracy) : nil,
            timeIso: CollectCapture.isoTimestamp(epochMs: millis)
        )
        lock.lock()
        reading = next
        lock.unlock()
    }
}
