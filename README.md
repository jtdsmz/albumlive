# AlbumLive

`albumlive` 是一个 Android 相册与实况照片能力库。

库只负责相册数据读取、实况照片识别/拆解、Motion Photo 合成与保存。
权限申请由 App 自行处理。

## 功能

- 读取系统相册文件夹。
- 按文件夹读取图片、视频。
- 识别实况照片 / Motion Photo。
- 拆解实况照片，输出封面图和视频。
- 用 JPEG 封面图 + 视频合成 Motion Photo。
- 保存 Motion Photo 到系统相册，主流系统（小米，oppo，vivo，华为，荣耀）可识别。
- 支持常见单文件 Motion Photo 和同名图片/视频配对实况。

## 引入

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

添加依赖：

```kotlin
dependencies {
    implementation("com.github.jtdsmz:albumlive:<Tag>")
}
```

## 权限

App 须自行声明和申请相册权限。

Manifest 示例：

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

权限申请建议：

- Android 13+：申请 `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`。
- Android 14+：如支持部分相册访问，同时处理 `READ_MEDIA_VISUAL_USER_SELECTED`。
- Android 12-：申请 `READ_EXTERNAL_STORAGE`。
- Android 9-：保存到公共相册可能需要 `WRITE_EXTERNAL_STORAGE`。

## 基础用法

创建 API：

```kotlin
val albumLiveApi = AlbumLiveApi.create(context)
```

读取文件夹：

```kotlin
val folders = albumLiveApi.getFolders(MediaType.ALL).getOrThrow()
```

读取媒体列表：

```kotlin
lifecycleScope.launch {
    albumLiveApi.getMediaItems(folder = null, mediaType = MediaType.ALL)
        .collect { result ->
            result.onSuccess { item ->
                // item.uri 可用于预览
                // item.path 有值时可用于实况检测/拆解/合成
            }
        }
}
```

异步标记实况照片：

```kotlin
val isLivePhoto = albumLiveApi.checkLivePhoto(item.path)
```

拆解实况照片：

```kotlin
val result = albumLiveApi.extractLivePhotoAssets(
    item = item,
    outputDir = cacheDir.absolutePath,
    outputFilePrefix = "live_${item.id}"
)

result.onSuccess { assets ->
    val coverPath = assets.coverImagePath
    val videoPath = assets.videoPath
}
```

合成 Motion Photo：

```kotlin
val result = albumLiveApi.createMotionPhoto(
    coverPath = coverJpegPath,
    videoPath = videoPath,
    options = CreateMotionPhotoOptions(
        outputDir = File(cacheDir, "motion_photo"),
        oplusOwner = "YourAppName"
    )
)
```

保存已有 Motion Photo 到相册：

```kotlin
val result = albumLiveApi.saveMotionPhoto(
    motionPhotoPath = motionPhotoPath,
    options = SaveMotionPhotoOptions(
        relativePath = "Pictures/YourAppName/"
    )
)
```

合成并保存：

```kotlin
val result = albumLiveApi.createAndSaveMotionPhoto(
    coverPath = coverJpegPath,
    videoPath = videoPath,
    createOptions = CreateMotionPhotoOptions(
        outputDir = File(cacheDir, "motion_photo"),
        oplusOwner = "YourAppName"
    ),
    saveOptions = SaveMotionPhotoOptions(
        relativePath = "Pictures/YourAppName/"
    )
)
```

## API

统一入口：

```kotlin
class AlbumLiveApi
```

### AlbumLiveApi

核心方法：

```kotlin
fun getFolders(mediaType: MediaType = MediaType.ALL): Result<List<AlbumFolder>>
fun getMediaItems(folder: AlbumFolder?, mediaType: MediaType = MediaType.ALL): Flow<Result<MediaItem>>
fun checkLivePhoto(path: String): Boolean
suspend fun extractLivePhotoAssets(item: MediaItem, outputDir: String, outputFilePrefix: String): Result<LivePhotoAssets>
suspend fun createMotionPhoto(coverPath: String, videoPath: String, options: CreateMotionPhotoOptions): Result<File>
fun saveMotionPhoto(motionPhotoPath: String, options: SaveMotionPhotoOptions): Result<Uri>
suspend fun createAndSaveMotionPhoto(coverPath: String, videoPath: String, createOptions: CreateMotionPhotoOptions, saveOptions: SaveMotionPhotoOptions): Result<Uri>
```

接口说明：

| 方法 | 参数 | 返回值 | 说明 |
| --- | --- | --- | --- |
| `create(context)` | `context`：任意 Android `Context` | `AlbumLiveApi` | 创建 API 实例，内部使用 `applicationContext`。 |
| `getFolders(mediaType)` | `mediaType`：读取类型，默认 `ALL` | `Result<List<AlbumFolder>>` | 读取相册文件夹。失败会进入 `Result.failure`，不会伪装成空列表。 |
| `getMediaItems(folder, mediaType)` | `folder`：指定文件夹，传 `null` 读取全部；`mediaType`：读取类型 | `Flow<Result<MediaItem>>` | 按时间倒序输出媒体项。读取失败会发出 `Result.failure`。 |
| `checkLivePhoto(path)` | `path`：本地真实文件路径 | `Boolean` | 判断单个文件是否为实况照片 / Motion Photo。耗时，建议放 IO 线程。 |
| `extractLivePhotoAssets(item, outputDir, outputFilePrefix)` | `item`：媒体项；`outputDir`：输出目录路径；`outputFilePrefix`：输出文件名前缀 | `Result<LivePhotoAssets>` | 拆解实况照片，输出封面图和视频。 |
| `createMotionPhoto(coverPath, videoPath, options)` | `coverPath`：JPEG 封面路径；`videoPath`：视频路径；`options`：合成配置 | `Result<File>` | 合成单文件 JPEG Motion Photo。 |
| `saveMotionPhoto(motionPhotoPath, options)` | `motionPhotoPath`：已生成的 Motion Photo 路径；`options`：保存配置 | `Result<Uri>` | 保存到系统相册。保存前会校验文件是否可识别为实况。 |
| `createAndSaveMotionPhoto(coverPath, videoPath, createOptions, saveOptions)` | 合成参数 + 保存参数 | `Result<Uri>` | 先合成，再保存到系统相册。 |

### Options

`CreateMotionPhotoOptions`

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `outputDir` | 是 | 合成文件输出目录。建议传 App cache 目录或临时工作目录。 |
| `outputFileNamePrefix` | 否 | 输出文件名前缀。为空时默认使用 `motion_photo`。 |
| `oplusOwner` | 否 | OPPO / OnePlus / Realme 系 Motion Photo XMP 的来源标记，会写入 `OpCamera:MotionPhotoOwner`。不确定可传 App 名或 `null`。 |
| `vivoLivePhotoId` | 否 | vivo 实况照片扩展信息 ID。一般传 `null`，由库生成；只有需要和已有 vivo live photo id 对齐时才传。 |

`SaveMotionPhotoOptions`

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `relativePath` | 是 | 保存到系统相册的相对路径，例如 `Pictures/YourAppName/`。Android 10+ 写入 `MediaStore.RELATIVE_PATH`；Android 9- 会映射到外部存储目录。 |
| `displayName` | 否 | 保存后的文件名。为空时使用源文件名。非 `.jpg/.jpeg` 后缀会自动补 `.jpg`。 |

### Models

`MediaType`

| 值 | 说明 |
| --- | --- |
| `ALL` | 图片和视频。 |
| `IMAGE` | 只读取图片。 |
| `VIDEO` | 只读取视频。 |

`AlbumFolder`

| 字段 | 说明 |
| --- | --- |
| `bucketId` | MediaStore 文件夹 ID。 |
| `folderName` | 文件夹展示名。只适合展示，不建议作为唯一身份。 |
| `folderPath` | 文件夹路径。库按它优先做文件夹精确读取。 |
| `coverUri` | 文件夹封面媒体 Uri。 |
| `coverPath` | 文件夹封面真实路径，可能为空。 |
| `mediaCount` | 文件夹内媒体数量。 |
| `lastModifiedTime` | 最近修改时间，单位为 MediaStore 返回值。 |

`MediaItem`

| 字段 | 说明 |
| --- | --- |
| `id` | MediaStore 媒体 ID。 |
| `type` | 媒体类型。 |
| `uri` | 媒体 Uri，适合预览。 |
| `path` | 本地真实路径，可能为空。实况检测、拆解、合成依赖它。 |
| `bucketId` | 文件夹 ID。 |
| `displayName` | 文件名。 |
| `mimeType` | MIME 类型。 |
| `size` | 文件大小，单位 byte。 |
| `dateAdded` | 添加时间，单位为 MediaStore 返回值。 |
| `dateModified` | 修改时间，单位为 MediaStore 返回值。 |
| `folderName` | 文件夹展示名。 |
| `folderPath` | 文件夹路径。 |
| `duration` | 视频时长，图片为 `0`。 |
| `width` / `height` | 媒体宽高，可能为 `0`。 |

`LivePhotoAssets`

| 字段 | 说明 |
| --- | --- |
| `coverImagePath` | 拆解出的封面图路径。 |
| `videoPath` | 拆解出的视频路径。 |

## 注意

- `MediaItem.uri` 适合图片/视频预览。
- `MediaItem.path` 可能为空，尤其是 Android 10+ 或部分相册授权场景。
- 实况检测和拆解依赖真实文件路径；调用前应确认 `path` 不为空。
- `createMotionPhoto` 要求封面图是 JPEG。
- 大文件检测和拆解建议放在 IO 线程执行。
- 库不保存实况识别缓存；如需避免重复检测，请在 App 层缓存 `checkLivePhoto` 结果。
- 库不保存 UI 状态；`MediaItem` 不包含实况标记字段，App 可自行包装 UI model。

## License

MIT License. See [LICENSE](LICENSE).
