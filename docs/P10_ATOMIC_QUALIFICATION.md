# P10 原子操作完整 host 验收

P10 的十五类完整 host 门槛均已通过，工作包随收尾 PR 受保护合入计为完成。
各矩阵按链接文档记录的准确软件源分别验收，并保留各自最终结果与生命周期。
硬件使用完整 assertion-enabled float
factory，保留两颗 16-lane/eight-warp CU、原 SRAM 和支持容量。

Storage、image 和混合语料走原 Mesa/Gallium 与生产 Linux compute 入口；
二十四条 global 程序是原 RV32 driver-level 语料。整数门槛覆盖 add、exchange、
xor、or、and、signed/unsigned min/max 和 CAS；image 另含有限 R32F exchange。
独立 oracle 检查调用者/逐 invocation 源内顺序、精确旧值、最终字及物理 guard，
原 held/retired、fence、PTE/context/MMU 清理和运行后输入检查均通过。

## 结果与复现索引

第一列是 `scripts/qualify_compute_owner.py --case` 的值，链接包含准确源、
最终结果、边界及复现命令。阶段数包含故障和健康复用；不表示独立原程序的数量。

| 资格入口（结果与复现） | case/阶段 | 实际旧值 | DUT/RAM 生命周期与关键检查 |
| --- | ---: | ---: | --- |
| [`storage-atomics`](GALLIUM_STORAGE_ATOMICS.md#complete-owner-storage-atomic-stage-p10) | 17（15 成功，2 个权限/边界拒绝） | 120 | 每 case 独立 reset |
| [`storage-atomics-delayed-ack`](GALLIUM_STORAGE_ATOMICS.md#complete-owner-delayed-ack-execution-p10) | 17 | 120 | 每 case 独立 reset；目标 B 至少扣留 37 周期 |
| [`storage-atomics-recovery`](GALLIUM_STORAGE_ATOMICS.md#complete-owner-physical-faults-and-continuous-reuse-p10) | 60 | 300 | 同 DUT/RAM/单 reset；30 R 错误、15 W 错误 |
| [`storage-atomics-accepted-write-error`](GALLIUM_STORAGE_ATOMICS.md#complete-owner-committed-write-errors-and-continuous-reuse) | 30 | 180 | 同 DUT/RAM/单 reset；15 accepted B 错误 |
| [`image-atomics-contended`](TYPED_IMAGE_LIVE_OWNER.md#same-texel-atomic-competition-p10) | 55 | 840 | 每 case 独立 reset；五类 target、R32I/UI 与有限 R32F exchange |
| [`image-atomics-accepted-write-error`](TYPED_IMAGE_LIVE_OWNER.md#committed-image-write-errors-and-continuous-reuse) | 110 | 1260 | 同 DUT/RAM/单 reset；55 accepted B 错误 |
| [`image-storage-atomics`](MIXED_COMPUTE_ATOMICS.md) | 66 | 1056 | 每 case 独立 reset；原双视图与源内顺序 |
| [`global-atomics`](GLOBAL_ATOMIC_TRANSLATION.md#original-global-amo-source-corpus) | 24 | 192 | 每 case 独立 reset；四个页/末字位置 |
| [`global-atomics-delayed-ack`](GLOBAL_ATOMIC_TRANSLATION.md#complete-owner-global-delayed-acknowledgements) | 24 | 192 | 每 case 独立 reset；目标 B 至少扣留 257 周期 |
| [`global-atomics-recovery`](GLOBAL_ATOMIC_TRANSLATION.md#complete-owner-global-physical-faults-and-continuous-reuse) | 96 | 432 | 同 DUT/RAM/单 reset；48 R 错误、24 W 错误 |
| [`global-atomics-accepted-write-error`](GLOBAL_ATOMIC_TRANSLATION.md#complete-owner-accepted-first-write-errors) | 48 | 240 | 同 DUT/RAM/单 reset；24 accepted B 错误 |
| [`global-atomics-cached`](GLOBAL_ATOMIC_TRANSLATION.md#explicit-cached-compute-kernargs) | 24 | 192 | 每 case 独立 reset；两页 cached PTE |
| [`global-atomics-cache-alias`](GLOBAL_ATOMIC_TRANSLATION.md#complete-owner-warm-cached-recovery-execution) | 48 | 240 | 同 DUT/RAM/单 reset；24 accepted B 错误；实际 L1/L2 预热、失效 ACK、错误后下层重取与回填 |
| [`global-atomics-uc-cache-alias`](GLOBAL_ATOMIC_TRANSLATION.md#complete-owner-uccache-alias-execution) | 48 | 240 | 同 DUT/RAM/单 reset；24 accepted B 错误；逐写权威下层读、无分配 |
| [`global-atomics-reservation-wait`](GLOBAL_ATOMIC_TRANSLATION.md#complete-owner-external-reservation-wait-execution) | 48 | 384 | 每 case 独立 reset；实际 EBUSY、257 周期空闲、受控释放；UC/cached 各 24 |

连续矩阵保留同一 DUT、RAM、单次初始 reset、原 command ID 和全部先前物理
分配；新 owner 只追加原始不重叠分配。逐 case reset 的矩阵保留各自范围。
已提交写错误要求首写真正提交后才返回错误，并检查真实 EIO/健康 fence、
幸存调用者历史及精确部分提交。Storage 的只读与 following-word 两项是实际
硬件执行拒绝，物理写和总线错误均为零。

## Reservation、取消与后续门槛

Reservation 矩阵使用原 GEM 上唯一的外部 CPU producer fence，在真实
`-EBUSY` 和零硬件提交后观察至少 257 个完整 DUT 空闲周期，再受控释放并执行
原 consumer。每个 case 从空闲证明到执行/清理共用一个 DUT；不同 case 分别 reset。
这是原 Linux OS boundary 验证，未启动 Linux 内核，也未建立 GPU producer
跨命令 coherence。

P07/P08 依赖保留各自已验收范围。取消/reset/drain 采用
[原 buffer R32F 延迟 R/B 两组结果](TYPED_IMAGE_LIVE_OWNER.md#actual-delayed-rb-cancellation-and-healthy-reuse)：
准确执行源 `4a60abb30654a5b96aa4d5cc096e466f4626749e`，每组同 DUT/RAM、
实际统一 reset、ECANCELED fence、精确 partial-cancel 字节、32 周期无晚到流量、
真实清理和健康复用。该结果保留选定 R32F 取消切点的范围。

GPU producer 与 warmed consumer 的跨 owner/命令和跨域 publication/barrier/fence
属于 P11；完整资源共存、极限和 robustness 属于 P12。当前
`fullResourceQualification=false`；完整 API、物理时钟和 2-CU 640×480/60 FPS
保留后续工作包的验收要求。

## 复现完整 DUT 门槛

从相应结果链接记录的准确软件源准备 matching complete-float executor，并保持
原模型、编译器/SDK、checker 和源输入绑定。逐项选择上表的 `CASE`：

```sh
PYTHONDONTWRITEBYTECODE=1 "$OWNER_PYTHON" -B scripts/qualify_compute_owner.py \
  --executor "$EXECUTOR" --executor-qualification "$EXECUTOR_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$OWNER_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case "$CASE" --timeout 14400
```

源、模型和完整 executor 检查必须在执行前后通过。CPU capture、MMIO discovery
和组件测试各自保留范围；完整矩阵要求实际 DUT 执行和独立字节 oracle。结束的
编译、capture、RAM、会话和日志按消费者/链接/引用及后续用途审计后删除，保留
后续工作仍需使用的共享模型和工具。
