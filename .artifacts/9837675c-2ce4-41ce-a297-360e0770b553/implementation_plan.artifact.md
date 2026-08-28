# Fix Unresolved Reference 'etSourceBox' in MainActivity

The `MainActivity.kt` file currently contains only a code snippet without the necessary Activity class structure, imports, or view references. This leads to the `Unresolved reference 'etSourceBox'` error. This plan will restore the Activity structure and use View Binding to properly resolve the UI references.

## Proposed Changes

### [Component] Build Configuration

#### [MODIFY] [build.gradle.kts](file:///C:/Users/cnkha/AndroidStudioProjects/NavAIDR/app/build.gradle.kts)
Enable View Binding to allow safe and easy access to views defined in `activity_main.xml`.

### [Component] Main Activity

#### [MODIFY] [MainActivity.kt](file:///C:/Users/cnkha/AndroidStudioProjects/NavAIDR/app/src/main/java/com/isro/navaidr/MainActivity.kt)
1. Add the missing `package` declaration and necessary imports.
2. Define the `MainActivity` class inheriting from `AppCompatActivity`.
3. Set up View Binding in `onCreate`.
4. Wrap the existing snippet into a `fetchAndDrawRoutes` function.
5. Define parameters for `fetchAndDrawRoutes` to resolve other missing references (`routes`, `limit`, `searchedQueryText`, `searchMarker`, `sourceMarker`).

## Verification Plan

### Automated Tests
- Run `./gradlew :app:compileDebugKotlin` to verify that the unresolved reference error is fixed.

### Manual Verification
- Deploy the app to a device/emulator to ensure `MainActivity` launches correctly and the layout is inflated.
