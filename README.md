# Create: AeroTools / 机械动力：航空学工具

Minecraft 1.21.1、NeoForge 平台的 Create Aeronautics 工具附属模组。

## 内容

- 万向传动端口和黄铜、安山万向轴：连接物理结构与结构或世界，传递 Create 动能。
- 气动端口：通过红石或侧面控件设定伸缩目标。
- 普通及高级收纳器：保存、释放单个结构或关联结构组。
- 生存物理工具枪：操作物理结构，使用耐久或背部气罐供能。

## 开发

使用 JDK 21。依赖版本在 `gradle.properties` 中定义；运行依赖还包括 Create、Sable、Simulated 和 Aeronautics。
Aeronautics 开发运行包需要放入 `libs/`，构建脚本会将其中的 jar 复制到开发客户端的 `run/mods/`。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

构建产物位于 `build/libs/`。服务器配置由 NeoForge 管理，配置项说明保持英文。
当前版本号为 `0.2.0`，本轮稳定性修复沿用该版本号。
`build` 会自动检查资源 JSON、中英文翻译键覆盖和旧命名空间残留；也可以单独运行 `verifyModResources`。

## 实现边界

端口通过 Create 的邻居发现与传动比接口参与动能网络，不直接改写对端转速。
物理更新只处理事件所属的 Sable 系统，并使用事件给出的模拟步长。
普通区块卸载保留链接；结构组装、拆解使用短暂的迁移宽限；明确破坏端口立即拆轴。

轴使用盒形刚体与关节，允许与世界及第三方结构碰撞，关闭与两端所属结构的接触，避免关节和碰撞互相争抢。
当前 Sable 盒形接口没有原位调整尺寸的方法，因此伸缩后仍需重建碰撞体；重建会延续线速度和角速度，但仍需进行物理稳定性测试。
高级收纳器按整组恢复处理；加载失败时回滚本次创建的结构，保留物品中的原始数据。
普通收纳器也使用同一恢复与回滚流程。轴回收入包失败时，剩余物品在玩家位置掉落，包括创造模式。

## 代码入口

- `block/DriveLinkTracker`：链接、回收、卸载和迁移状态。
- `block/DriveShaftPhysics`：碰撞体、关节和伸缩电机。
- `block/DrivePortBlockEntity`：Create 动能传播与护目镜信息。
- `item/StructureGroups`、`CapsulePayloads`：结构组与收纳序列化。
- `util/WorldSpace`：结构局部坐标和世界坐标转换。
- `client/`、`network/`：渲染、预览和工具枪同步。

## 回归测试

物理行为不能由编译结果证明。变更后的游戏内检查步骤见 `docs/regression-checks.md`。

许可证：MIT。第三方代码来源与声明见 `NOTICE-SIMULATED.md`。
