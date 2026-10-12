# Local image pixel validation

This private bridge only receives base64 from a user-selected local file. The TypeScript caller rejects unsupported data URLs and dimensions outside 1–1024 before invoking it, then requires decoder success and matching pixel dimensions. It returns no image pixels to other services. There is no network image loading or 200 KB upload limit.

- Android: `BitmapFactory` bounds pass, followed by actual `decodeByteArray`; decoded bitmaps are recycled in `finally`.
- iOS: ImageIO `CGImageSourceCreateImageAtIndex` with `kCGImageSourceShouldCacheImmediately = true`; native objects are ARC-managed. `NSMutableDictionary`/`NSString` are used for Core Foundation bridging.
- Harmony: `ImageSource.createPixelMap` and decoded image information; the PixelMap and ImageSource are released in `finally`.

The JS wrapper times out decoding after 15 seconds and ignores late callbacks. An optional aborted signal rejects both the file read and decoder result. The family form also owns its upload generation, so navigating/changing the destination cannot apply an old image.

Validation:

```sh
npm run test --workspace frontend -- native-image.test.ts
swift frontend/tests/native-image-system.swift # macOS ImageIO primitive smoke check
npm run typecheck
npm run build:android
npm run build:ios
npm run build:harmony
```

CLI builds produce native source/resources; they are not SDK compilation or device validation. The macOS smoke test proves full PNG acceptance and 33-byte header-only PNG rejection with the same ImageIO primitive, but does not prove the iOS UTS runtime bridge. Android/Harmony decoder runtime and native memory lifecycle still require device validation.

Official references:

- https://developer.android.com/reference/android/graphics/BitmapFactory
- https://developer.apple.com/documentation/imageio/cgimagesourcecreateimageatindex(_:_:_:)
- https://developer.apple.com/documentation/imageio/kcgimagesourceshouldcacheimmediately
- https://gitee.com/openharmony/docs/blob/master/en/application-dev/reference/apis-image-kit/arkts-apis-image-ImageSource.md
- https://doc.dcloud.net.cn/uni-app-x/plugin/uts-for-ios.html
