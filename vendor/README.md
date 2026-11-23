# 编译依赖来源

官方坐标：`io.github.libxposed:api:102.0.0`，仅用于编译，不打入模块 DEX。

- AAR：[Maven Central](https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar)
- 官方 [SHA256](https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar.sha256)：`423484A6E1807E7A423C4B88FCD8176D104318259D91791877FED88FE91479D0`
- `api-102.0.0.jar` 是 AAR 中 `classes.jar` 的原样提取，构建脚本每次核对两者内容哈希。
- 上游源码和 Apache 2.0 许可：[libxposed/api](https://github.com/libxposed/api)。

依赖随源码提供，可用于离线构建。

运行时使用 `io.github.libxposed:service:102.0.0` 和 `io.github.libxposed:interface:102.0.0`。两份 JAR 均从相应 AAR 原样提取，构建脚本验证 JAR 哈希。APK 随附 [Apache 2.0 许可证](https://github.com/libxposed/service/blob/master/LICENSE)。

| AAR | 官方 SHA256 |
| --- | --- |
| [service 102.0.0](https://repo.maven.apache.org/maven2/io/github/libxposed/service/102.0.0/service-102.0.0.aar) | `A665E4AF4638924047F5D5CB610553E15BC2C0EA2CE64A27AFB6DEE257FF014C` |
| [interface 102.0.0](https://repo.maven.apache.org/maven2/io/github/libxposed/interface/102.0.0/interface-102.0.0.aar) | `4E0B76D6050BE1D3DC0D35525180AD7D9B60A47FD8D8FE1D18C2BDC840BE9DDB` |
