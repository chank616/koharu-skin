<a href="https://store.line.me/stickershop/product/14913987/ja">
  <img src="https://stickershop.line-scdn.net/stickershop/v1/sticker/389899462/android/sticker.png?v=1" width="200" align="right" alt="钓到鱼的 Q 版海凪小春" />
</a>

<div align="center">

# Koharu Skin

一个用 Go 写的 Minecraft 皮肤站。

[关于项目](#关于项目) · [从源码运行](#从源码运行) · [致谢](#致谢)

</div>

## 关于项目

Koharu Skin 是一个使用 Go 开发的 Minecraft 皮肤站，参考 [Blessing Skin](https://github.com/bs-community/blessing-skin-server) 的设计与实现。

原项目近年更新不多，因此决定用 Go 重新实现，并继续维护。

项目名取自喜欢的角色——《SLOW LOOP－女孩的钓鱼慢活》中的海凪小春（Koharu）。

## 当前状态

目前已提供基础 HTTP 服务和健康检查接口。账号、角色、皮肤与披风管理、衣柜和动态插件等功能还在后面。

<br clear="both" />

## 从源码运行

需要 Go 1.26.5 或更高版本。

在项目根目录执行：

```sh
go run ./cmd/server
```

服务默认监听 `127.0.0.1:8080`，按 `Ctrl+C` 停止。

## 健康检查

访问 <http://127.0.0.1:8080/health>，正常运行时返回 HTTP 200：

```json
{"status":"ok","service":"koharu-skin"}
```

## 构建

```sh
go build ./cmd/server
```

## 致谢

- [Blessing Skin](https://github.com/bs-community/blessing-skin-server)：皮肤站业务与实现参考。
- [Gin](https://github.com/gin-gonic/gin)：HTTP 框架。

小春的 Q 版插图来自芳文社发布的 [《釣りも日常も『スローループ』》LINE 贴纸](https://store.line.me/stickershop/product/14913987/ja)，由原作者うちのまいこ绘制。

© Houbunsha Co.,Ltd
