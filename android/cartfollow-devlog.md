# Human Cart Simulator 开发进度记录

> 所属项目：自主跟随购物车原型
> 代码位置：`dev/OpenBot/android/robot/src/main/java/org/openbot/cartfollow/`
> 开发分支：`feature/human-cart-simulator`（Phase 1）/ `feature/distance-control`（Phase 2 起）
> 最后更新：2026-07-06

---

## 1. 模块总览

Human Cart Simulator 是购物车跟随功能的上位机核心模块，在 OpenBot App 中新增一个功能页面（"Cart Simulator"），实现基于手机摄像头人物检测的跟随控制闭环。

### 文件清单

| 文件 | 行数 | 作用 |
|------|------|------|
| `HumanCartSimulatorFragment.java` | 464 | 主 UI Fragment：摄像头预览 + 检测框绘制 + 确认/重拍/取消 + 倒计时 + 距离调试显示 |
| `ControlGenerator.java` | 110 | 控制算法：基于 DistanceState 决定 forward，转向由 xError 决定 |
| `FollowStateMachine.java` | 262 | 完整状态机：管理两阶段目标初始化、重识别、倒计时、跟随、丢失、搜索、停止 |
| `FollowState.java` | 14 | 状态机枚举：`IDLE / CAPTURE_TARGET / LOCKED_PENDING_CONFIRM / CONFIRMED_ARMED / REACQUIRE_TARGET / READY_TO_FOLLOW / FOLLOW / LOST / SEARCH / STOP` |
| `DistanceState.java` | 7 | 距离状态枚举：`TOO_FAR / OK / TOO_CLOSE / UNKNOWN` |
| `ImageSetpointDistanceEstimator.java` | 112 | 图像伺服距离估计器：基于初始化 setpoint 输出 height_scale / area_scale / bottom_shift / state / confidence |
| `TargetMemory.java` | 167 | 目标记忆：confirmedBbox、面积、上下身 HSV 颜色直方图、动态位置、距离 setpoint |
| `TargetMatcher.java` | 79 | 目标匹配：position + size + color + confidence 融合评分（ReID 接口预留） |
| `ReIDFeatureExtractor.java` | 5 | ReID 接口占位，尚未接入真实 embedding 推理 |
| `HumanCommandInterpreter.java` | 52 | 中文指令解释器：支持 DistanceState 重载，输出距离感知指令 |
| `fragment_human_cart_simulator.xml` | 218 | 布局文件：OverlayView + 指令文本 + 快照确认面板 + 倒计时 + 调试信息 + 底部面板 |

### 集成点（在 OpenBot App 中的入口）

| 文件 | 修改内容 |
|------|----------|
| `FeatureList.java` | 新增 `CART_SIMULATOR` 类别，显示在主菜单 |
| `MainFragment.java` | 添加 Cart Simulator 的导航路由 |
| `nav_graph.xml` | 注册 `cartSimFragment` 导航目标 |
| `strings.xml` | 新增 `cart_simulator` / `cart_sim_start` / `cart_sim_idle` 字符串 |

---

## 2. 当前实现状态

### 2.1 已完成（commit `dd6aa95` + `409d85f` + `4da208a` + Phase 2）

| 功能 | 状态 | 说明 |
|------|------|------|
| 人物检测（MobileNet-SSD） | 已完成 | 复用 OpenBot 现有 `Detector`，筛选 `classType="person"` |
| 两阶段目标初始化 | 已完成 | `CAPTURE_TARGET → LOCKED_PENDING_CONFIRM → CONFIRMED_ARMED`，采集时记录 confirmedBbox、面积、上下身颜色直方图、距离 setpoint，截图供用户确认 |
| 用户确认 / 重拍 / 取消 | 已完成 | 确认面板含快照预览与三按钮，状态切换正确 |
| 目标记忆 `TargetMemory` | 已完成 | 保存 confirmedBbox、confirmedArea、上下身 HSV 直方图、动态 lastBbox/lastCenter/lastArea/lastSeenTime、距离 setpoint（desiredHeightRatio/areaRatio/bottomRatio） |
| 目标匹配 `TargetMatcher` | 已完成 | position(0.40) + size(0.20) + color(0.30) + confidence(0.10) 融合评分，阈值 0.5；ReID 接口预留未接入 |
| 确认后重识别启动 | 已完成 | `CONFIRMED_ARMED → REACQUIRE_TARGET`，连续 `REACQUIRE_MATCH_N=8` 帧匹配后进入倒计时 |
| 倒计时启动 | 已完成 | `READY_TO_FOLLOW` 倒计时 3 秒后进入 FOLLOW |
| 完整状态机 `FollowStateMachine` | 已完成 | `IDLE → CAPTURE → CONFIRM → REACQUIRE → COUNTDOWN → FOLLOW → LOST → SEARCH → STOP` 全链路 |
| `LOST → SEARCH → STOP` 执行逻辑 | 已完成 | 连续 `FOLLOW_LOST_M=10` 帧未匹配进入 LOST；LOST 持续 `LOST_TO_SEARCH_MS=800ms` 进入 SEARCH；SEARCH 超时 `SEARCH_TIMEOUT_MS=5000ms` 进入 STOP；期间重新匹配则回 FOLLOW |
| **初始化距离标定 + 图像伺服** | 已完成（Phase 2） | `ImageSetpointDistanceEstimator` 基于采集时记录的 setpoint 输出 `height_scale / area_scale / bottom_shift`，无需恢复真实米制距离 |
| **DistanceState 输出** | 已完成（Phase 2） | 输出 `TOO_FAR / OK / TOO_CLOSE / UNKNOWN`，替代线性 distError；UNKNOWN 时停车 |
| **ControlGenerator 基于 DistanceState** | 已完成（Phase 2） | forward 由 DistanceState 决定：TOO_FAR→MAX_FORWARD，其余→0；移除硬编码 TARGET_H_RATIO/K_DIST/TOO_CLOSE_H_RATIO |
| **距离调试显示** | 已完成（Phase 2） | Simulator 显示 `dist / hScale / aScale / bShift / distConf` |
| **距离感知指令** | 已完成（Phase 2） | `HumanCommandInterpreter` 新增 DistanceState 重载：OK→"保持距离"、UNKNOWN→"距离不明，请停止" |
| 转向方向修正 | 已完成 | `FLIP_TURN=true`，commit `409d85f` 修正 |
| UI 模式切换 | 已完成 | 开关控制检测启停，启动后锁定模型选择 |
| 置信度调节 | 已完成 | +/- 按钮以 5% 步进调节，范围 5%-95% |
| 模型选择 | 已完成 | 支持本地和 URL 模型，修复了 URL 模型的错误提示 |
| 检测框可视化 | 已完成 | 绿色目标框 / 黄色候选框 / 白色普通行人框 / 红色匹配失败框 |
| 快照确认面板 | 已完成 | LOCKED_PENDING_CONFIRM 时显示候选目标截图 + 确认/重拍/取消 |
| 倒计时显示 | 已完成 | READY_TO_FOLLOW 时显示剩余秒数 |
| 调试信息面板 | 已完成 | 显示 state / forward / turn / left / right / persons / fps / dist / hScale / aScale / bShift / distConf |
| 导航集成 | 已完成 | 已注册到主菜单 "Cart Simulator" 入口 |

### 2.2 核心控制算法（Phase 2 后）

```
输入：匹配目标 bbox + 画面尺寸 + 传感器角度 + TargetMemory(setpoint)
输出：Control(left, right) + DistanceEstimate

距离估计（ImageSetpointDistanceEstimator）：
  1. 处理 sensorOrientation 旋转
  2. currentHeightRatio = boxHeight / imgHeight
     currentAreaRatio   = boxArea / (imgW * imgH)
     currentBottomRatio = boxBottom / imgHeight
  3. heightScale = currentHeightRatio / desiredHeightRatio
     areaScale   = sqrt(currentAreaRatio / desiredAreaRatio)
     bottomShift = currentBottomRatio - desiredBottomRatio
  4. 校验：bbox 过小 / height_scale 与 area_scale 对数差异过大 → UNKNOWN
  5. 判态：heightScale < 0.85 → TOO_FAR
          heightScale > 1.15 → TOO_CLOSE
          否则                → OK

控制生成（ControlGenerator）：
  xError = target_centerX / imgWidth - 0.5
  turn = K_TURN × xError × (FLIP_TURN ? -1 : 1)
  forward =
    TOO_FAR  → MAX_FORWARD
    OK       → 0
    TOO_CLOSE→ 0  （首版不主动后退）
    UNKNOWN  → 0  （不确定就停）
  left = forward - turn, right = forward + turn
```

当前可调参数（`ControlGenerator`）：

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `K_TURN` | 1.5 | 转向灵敏度 |
| `MAX_FORWARD` | 0.6 | TOO_FAR 时的固定前进速度 |
| `MIN_CONFIDENCE` | 0.5 | 最小检测置信度 |
| `FLIP_TURN` | true | 转向方向翻转 |

距离估计参数（`ImageSetpointDistanceEstimator`）：

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `FAR_THRESHOLD` | 0.85 | heightScale 低于此值判定 TOO_FAR |
| `CLOSE_THRESHOLD` | 1.15 | heightScale 高于此值判定 TOO_CLOSE |
| `UNKNOWN_HEIGHT_DISAGREE` | 0.3 | height/area 对数差异上限 |
| `MIN_BBOX_HEIGHT_RATIO` | 0.1 | bbox 高度占比下限 |

当前状态机参数（`FollowStateMachine`）：

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `CAPTURE_FRAMES` | 15 | 采集帧数阈值 |
| `REACQUIRE_MATCH_N` | 8 | 重识别连续匹配帧数 |
| `FOLLOW_LOST_M` | 10 | FOLLOW 连续未匹配进入 LOST 的帧数 |
| `LOST_TO_SEARCH_MS` | 800 | LOST 进入 SEARCH 的延时 |
| `SEARCH_TIMEOUT_MS` | 5000 | SEARCH 超时进入 STOP |
| `COUNTDOWN_MS` | 3000 | 倒计时时长 |

---

## 3. 尚未实现（待开发）

### 3.1 关键缺失

| 功能 | 优先级 | 说明 |
|------|--------|------|
| **`vehicle.setControl()` 集成** | 中（阶段6） | 当前 Control 仅显示在 UI 上，未实际发送给底盘。硬件联调阶段在 `processFrame()` 中调用 `vehicle.setControl()` |
| **目标重锁定增强** | 中 | 当前 LOST/SEARCH 恢复复用 TargetMatcher，未加入 ReID 强确认。阶段3 处理 |
| **参数持久化** | 低 | 当前调参仅内存生效，重启恢复默认 |
| **参数 UI 面板** | 低 | K_TURN / MAX_FORWARD / 阈值等参数需通过代码修改，没有 UI 界面 |
| **bottomShift 参与判态** | 低 | 当前 bottomShift 仅用于显示，未参与距离状态判断。待 90° 旋转下方向实测验证后决定是否纳入 |

### 3.2 状态机（已完整实现）

```
IDLE ──(启动开关)──→ CAPTURE_TARGET ──(采集N帧)──→ LOCKED_PENDING_CONFIRM
                                                          │
                                              确认 / 重拍 / 取消
                                                          │
CONFIRMED_ARMED ──(检测到人)──→ REACQUIRE_TARGET ──(连续N帧匹配)──→ READY_TO_FOLLOW
                                                                        │
                                                                  倒计时3秒
                                                                        ↓
                                                                  FOLLOW
                                                                    │
                                                            连续M帧未匹配
                                                                    ↓
                                                                   LOST ──(800ms)──→ SEARCH ──(5s超时)──→ STOP
                                                                    │                  │
                                                                    └──(重新匹配)──────┴──→ FOLLOW

STOP ──(用户重新开始)──→ CAPTURE_TARGET
```

### 3.3 已知代码问题

| 问题 | 说明 |
|------|------|
| 目标选择策略仍依赖位置+颜色 | TargetMatcher 未接入 ReID，多人长时间交叉下可能误锁。阶段3 处理 |
| forward 限幅非对称 | forward 只允许 ≥0，不允许后退。安全优先，合理 |
| bottomShift 旋转方向待验证 | 90° 旋转下 boxBottom 映射方向需实测确认，当前仅显示不参与判态 |

---

## 4. 与下位机的接口

| 方向 | 协议 | 说明 |
|------|------|------|
| 上位机→下位机 | `c<left>,<right>` | 由 `Vehicle.sendControl()` 发送，范围 [-255,255] |
| 心跳 | `h<interval_ms>` | 由 `Vehicle` 自动管理 |

**当前状态：Cart Simulator 未调用 `vehicle.setControl()`，因此底盘不会运动。** 首版联调前需要先接通这个链路。

---

## 5. 后续开发计划

> 阶段划分对齐 `design/自主跟随购物车上位机软件开发计划.md` 与 `design/上位机软件开发 Phase 2——修正跟随距离控制计划书.md`

### Phase 1：状态机与目标初始化闭环（已完成）

- [x] 完整状态机 `IDLE → CAPTURE → CONFIRM → REACQUIRE → COUNTDOWN → FOLLOW → LOST → SEARCH → STOP`
- [x] 两阶段目标初始化 + 用户确认 + 重识别启动
- [x] TargetMemory / TargetMatcher / ControlGenerator 接入
- [x] Human Cart Simulator 实时提示与调试显示
- [ ] 多人干扰不切换目标（部分保证，待 ReID 增强）

### Phase 2：修正跟随距离控制（已完成）

- [x] 新增 `DistanceState` 枚举（`TOO_FAR / OK / TOO_CLOSE / UNKNOWN`）
- [x] 新增 `ImageSetpointDistanceEstimator`，输出 height_scale / area_scale / bottom_shift / state / confidence
- [x] `TargetMemory` 采集时记录 `desired_bbox_height_ratio / area_ratio / bottom_ratio`
- [x] 重构 `ControlGenerator`，forward 由 DistanceState 决定，移除硬编码 setpoint
- [x] `FollowStateMachine.FrameResult` 透传 distanceEstimate
- [x] Human Cart Simulator 显示 dist_state / hScale / aScale / bShift / distConf
- [x] `HumanCommandInterpreter` 纳入 distance state
- [x] 0.8-1.2 m 目标距离标定验证（初步测试基本通过，大多数情况下可以把距离保持为初始化时的距离。bShift在人远离的时候会向更负的方向变化，符合预期。）

**不在 Phase 2 范围**：`vehicle.setControl()` 接通（阶段6）、ReID 增强（阶段3）、障碍处理（阶段5）

### Phase 3：真实检测框数据闭环 + ReID

- [ ] 从 OpenBot Android 导出真实 person bbox crop
- [ ] PC 端验证 confirmedGallery / reid_score / reid_margin
- [ ] Android 端部署 osnet_x0_25（ONNX Runtime Mobile 主线）
- [ ] 多人/遮挡/重现场景验证

### Phase 4：单目深度辅助

- [ ] MiDaS / Depth Anything Android 部署调研
- [ ] 作为距离/障碍风险辅助，不替代安全状态机

### Phase 5：局部可通行空间与跟随式避障

- [ ] LEFT / CENTER / RIGHT 三方向 free score
- [ ] 候选动作 SLOW_FORWARD / LEFT_ARC / RIGHT_ARC / BLOCKED_WAIT

### Phase 6：硬件联调

- [ ] 接通 `vehicle.setControl()` 到底盘
- [ ] 真实车速/转向半径/延迟标定
- [ ] ToF / 超声波安全冗余

---

## 6. 提交历史

| Commit | 日期 | 说明 |
|--------|------|------|
| `dd6aa95` | 2026-07 | Add Human Cart Simulator for shopping cart follow debugging |
| `409d85f` | 2026-07 | Fix turn direction, confidence button height, and model selection |
| `4da208a` | 2026-07-02 | Add two-stage target init, target memory and full follow state machine |
| `173ef96` | 2026-07-06 | Add DistanceState and ImageSetpointDistanceEstimator for image-based visual servoing |
| `66bbf12` | 2026-07-06 | Calibrate distance setpoint in TargetMemory and refactor ControlGenerator to DistanceState |
| `c880025` | 2026-07-06 | Display distance state and scales in Human Cart Simulator |

---

## 7. 调试提示

- 使用 `/dev/OpenBot/android` 在 Android Studio 中打开工程
- 主菜单 → "Cart Simulator" 进入本模块
- 打开 Start 开关开始检测，关闭开关回到 IDLE
- 调试信息面板显示实时 state / forward / turn / persons / fps
- 中文指令文本仅供调试参考，实际不会发送给底盘
