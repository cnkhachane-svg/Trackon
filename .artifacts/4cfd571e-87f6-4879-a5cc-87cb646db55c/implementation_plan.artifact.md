# Fix App Stopping and GPS Lost Issues

The app is experiencing crashes (stopping) and incorrect GPS status reporting ("GPS Lost"). The primary causes include leaking GNSS callbacks, overwhelming the main thread with high-frequency sensor updates, and misleading logic for GPS availability.

## User Review Required

> [!IMPORTANT]
> The dead reckoning (AI Navigation) will now be allowed to start from the last known location if a fresh GPS fix is not immediately available. This ensures the app doesn't stay stuck on "Waiting for GPS".

## Proposed Changes

### MainActivity Refactoring

#### [MODIFY] [MainActivity.kt](file:///C:/Users/cnkha/AndroidStudioProjects/NavAIDR/app/src/main/java/com/isro/navaidr/MainActivity.kt)
- **Fix GNSS Callback Leak**: Properly unregister `GnssStatus.Callback` in `onPause`.
- **Throttle Sensor UI Updates**: Only update Accelerometer/Linear Acceleration text views every ~500ms instead of every event (100Hz).
- **Improve GPS Status Logic**:
    - Differentiate between "Searching for GPS" and "GPS Lost/Disabled".
    - Update `isGnssAvailable` based on actual status updates, not just the presence of a provider.
- **Enable Dead Reckoning from Start**: Use the last known location to initialize position so dead reckoning can provide updates even before the first GPS fix.
- **Deduplicate ONNX Session**: Remove redundant ONNX session fields from `MainActivity` and use the ones already present in `EdgeNavigationEngine`.
- **Thread Safety**: Add basic synchronization or copy buffers before passing to the background thread for inference.

### EdgeNavigationEngine Improvements

#### [MODIFY] [EdgeNavigationEngine.kt](file:///C:/Users/cnkha/AndroidStudioProjects/NavAIDR/app/src/main/java/com/isro/navaidr/EdgeNavigationEngine.kt)
- **Exposure of Session**: Ensure `EdgeNavigationEngine` can be used as the primary engine without `MainActivity` needing its own session.

## Verification Plan

### Automated Tests
- Run `:app:compileDebugKotlin` to ensure no regressions in build.

### Manual Verification
- Deploy to device/emulator.
- Check if "GPS Lost" appears only when GPS is actually disabled.
- Check if the app remains responsive during high-frequency sensor movements.
- Verify that location updates (via dead reckoning) occur even when GPS signal is shielded (if last known location was available).
