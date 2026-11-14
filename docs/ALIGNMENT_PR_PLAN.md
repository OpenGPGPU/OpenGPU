# GPU alignment PR 工作清单

本文件是剩余 alignment 工作的执行账本。目标保持为完整的 H6–H8 功能、
资源、编译器/驱动/API 接口和产品验收：**2 CUs、640×480、60 FPS**。
保留支持的 16-lane/eight-warp、SRAM 和资源容量；720p、4 CU 是可选扩展。
历史组件测试、CPU 协议测试和旧模型结果分别保留其范围。

## 当前总览

- 最近已合入：[PR #818](https://github.com/OpenGPGPU/OpenGPU/pull/818)，
  complete owner compute-stage、执行器与产品资格基础；全部受保护 CI 通过。
- 本次建立的剩余基线为 **38 个 PR 工作包**：源码交付 3 项，功能/资源/API
  闭合 29 项，性能与最终验收 6 项。当前此基线中验收完成并合入 **0/38**。
- P01 的完整主机脚本已通过；104/112 初始化修复的六项 ASan/UBSan 补充检查也已通过。
  P02、P03 的代码已有实现和限定范围的测试，仍须独立交付验证。
- P07 atomic counter 的首条 increment 路径已通过真实 Mesa CPU 构建/capture、严格输入检查和 C++ capture parser；其余操作和实际 DUT 验收仍未完成。
- 当前开放的 alignment PR 为 **1**：P01 / [PR #819](https://github.com/OpenGPGPU/OpenGPU/pull/819)。

这里的 0/38 只统计本次列出的剩余工作，不是整个项目的历史完成率。
现有源码和组件证据不因列入闭合任务而被当作从零开始，也不会据此推算
一个未经验收的总完成百分比。

## 执行和状态规则

1. 每个编号对应一个有明确交付物与验收条件的 PR。实现可在依赖允许时由
   subagent 并行进行；**同时只开放 1 个 alignment PR，合入后再开下一个**。
2. 优先交付 P01 → P02 → P03，再按依赖推进功能与资源闭合。模型等待期间
   可以完成独立源码工作，不用旧96或小容量模型替代目标配置的验收。
3. 每行记录实际 PR 链接、源码/实现状态、目标验收状态。只有该行所有
   验收条件成立且 PR 已合入，才标为“完成”。代码先合入而实际 DUT 尚未
   验证时，明确记“源码已合入／目标验收待执行”，不计入完成数。
4. 源码交付 P01–P03 的验收是代码并集、相关本地测试及受保护 CI；其范围
   明确不含后续完整 DUT、API 或 FPS 门槛。其他行需要的实际执行不可用
   syntax check、CPU peer、synthetic RAM、单组件结果或 capability bit 替代。
5. 每个 PR 保留逻辑提交，不 squash；实现及本地验证通过后设为 ready，
   启用 rebase auto-merge，仍要求严格的 `ci-ok` 和最新分支。重放每批少于
   100 个提交；原始开发分支/冻结源码在所需交付和验证结束前保留。
6. 修改 RTL、资源或 ABI 后，重新绑定准确 factory/source/tool/model/binary。
   当前112 sample-stage 仍声明 `fullResourceQualification=false`；后续
   TCS/TES 等合同必须显式版本化。最终验收使用共同的完整目标配置，
   不把当前112自动视为完整 GLES 3.2 资源资格。
7. 文档只保留本清单的当前状态、最终结果、限制和复现命令，不追加运行
   日记或原始证据。结束的 build/model/log/cache 按仓库规则及时清理；
   活动消费者、未发布源码和仍需要的冻结输入保留。
8. 发现新的必需缺口时，在清单中公开新增或拆分编号、说明依赖，并显式
   更新总数；不通过缩小既定验收条件使任务变绿。

## 正式入口与分支用途

GPU 的正式交付入口是受保护的 [`main`](https://github.com/OpenGPGPU/OpenGPU/tree/main)。
下列开发分支保留尚未交付的源码，各有固定用途；不能因为其中已有更多代码，
就把该分支当作正式版本或把对应目标计为完成。

| 仓库 / 分支 | 用途与交付方式 |
|---|---|
| GPU `main` | 已通过受保护 CI 并合入的正式源码；最近基线为 #818 |
| GPU `codex/alignment-delivery-b` | P01 交付分支，包含本清单；只通过 PR 进入 main |
| GPU `codex/native-sample-dut-bridge` | 保留 P02/P03 的原开发源码，按本清单分批交付；不另外开放并行 PR |
| GPU `codex/owner-counter-live` | P07 的隔离实现分支；保留未交付工作，不因首条 CPU recipe 通过就宣告完成 |
| GPU `codex/alignment-host-integration` | 旧96模型的冻结输入；供现有验证消费者使用，不是新的完整目标版本 |
| Yosys `gpu-physical-pinned-v1` | GPU physical 工具的正式固定分支，提交 `1cf33ea3e5e98bf5ddb94a7c77535a0f1894af7f`；见下文 |

Yosys 正式版本见 [`gpu-physical-pinned-v1`](https://github.com/OpenGPGPU/yosys/tree/gpu-physical-pinned-v1)。
`codex/scalar-mux-feedback-walk` 是已合入的开发分支，`master` 是上游基线，
`dep-update` 用于上游依赖更新。工具镜像 `opengpgpu/yosys-physical:ebf2d34fabe1`
使用的是该正式合入结果的相同源码树；运行任务仍须显式选择正确工具配置，
不能据此假定所有默认任务都已切换。独立仓库不继承 GPU 的 PR/分支保护要求。

当前完整目标尚缺的关键验收是：新完整 factory/model 的真实执行、H7/H8
资源与 stage/API 闭合、六场景 VGA60，以及同配置匹配时序。清单后续各行
分别关闭这些缺口；已有 CPU/组件结果不替代这些验收。

## 已实现源码的串行交付

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P01 | GS、layered/sample、104/112 protected factory 与 compute 语料的源码交付 | 保留 main 修复和原逻辑提交；完整 host regression、GNC5/GNJ2 生产初始化检查、Scala/相关 CI 通过 | #818 | [#819](https://github.com/OpenGPGPU/OpenGPU/pull/819)；本地验证通过，CI/合入待完成 |
| P02 | 原 Mesa/native96 与 sample ABI6/7 编译、保护提交、资格及生命周期源码交付 | 单独重放前45个逻辑补丁和必要 merge-resolution；源码并集、链接/协议/拒绝检查与 CI 通过 | P01 | 已有实现，待独立交付 |
| P03 | tess sample/image 路由、ordinary112 双 owner oracle/controller、LIVE 反例及独立112 blit 入口源码交付 | 交付后39个补丁；保留 cleanup 退出竞态修复、真实 C 拒绝检查、43包 blit 守卫；与完整开发树逐路径核对并通过 CI | P02 | 已有实现，待独立交付 |

P02/P03 的分界按现有逻辑提交和依赖确定，保持代码可审查。它们已有的
CPU、受限 Linux 构建和局部 RTL 结果只证明各自记录的范围。

## 完整 host、H7 执行与内存合同

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P04 | 新完整目标 factory 的 RTL、模型和执行器资格 | 新鲜生成；全容量、assertions、源码/工具/分片/二进制绑定与原 probe 一致；具备可复现的实际执行入口。后续合同变化须重做此绑定 | P01–P03 | 待执行；当前旧96生成不关闭本项 |
| P05 | 原 Mesa graphics/sample 的完整 host 双 owner 执行 | 实际 shader、原 GEM/Sv32、同 RAM/无中间 reset；两次结果由独立图像/GNY2/guard oracle 验证；实际 retirement、PTE/shootdown/context cleanup ACK 全部成功 | P04 | 代码/CPU证据已有，实际 DUT 待执行 |
| P06 | 43个原调用者 framebuffer blit 的目标 host 执行 | 原43包及 source SHA 不变；精确字节/VM guard、source fault、延迟 R/B reset/drain、健康 ID 复用在目标 DUT 上通过 | P03、P04 | 112入口已实现，实际 DUT 待执行 |
| P07 | 原 GEM atomic-counter live 提交与完整操作语料 | 实际 Mesa→生产 ioctl→目标 DUT；inc/read/dec、动态索引及资源共存；独立返回集合/最终 counter/全字节 guard，保留真实 owner 至 cleanup ACK | P04 | increment 的真实 Mesa CPU/capture 检查通过；其余操作及 DUT 待完成 |
| P08 | Typed image load/store/atomic live 提交 | 原 image view、format/pitch/layer/权限与精确 writable view；实际 shader 结果、边界、fault/cancel/reset 及独立 oracle 通过 | P04、P07 | 待补齐 |
| P09 | Indirect compute 原始 source 与执行合同 | 专用 opcode14 capture；GPU产生的三维 grid、依赖 fence/snapshot、零工作、极限/越界、失败与复用均通过，不靠 opcode 白名单 | P04、P07 | 待补齐 |
| P10 | Storage/global/image atomic 完整操作与竞争 | 全规定操作、CAS 精确旧值、竞争与重排、延迟 ACK、reservation、只读/越界、故障后健康复用；实际完整 host 验证 | P07、P08 | 待闭合 |
| P11 | 多 owner、多命令和跨域 coherence/barrier | 同 RAM、无 reset，GPU producer 与 warmed consumer；SSBO/image/texture/attachment/DMA/counter/shared 的 publication、barrier、fence 和 cleanup 矩阵通过 | P05–P10 | 待补齐 |
| P12 | Compute 资源共存、极限与 robustness | 保留128本地线程/16KiB共享及4SSBO、4image、8counter、32texture、12UBO/default；同时使用、末地址、namespace/alias/permission、cancel/reset 均有完整 host 证据 | P07–P11 | 待闭合 |
| P13 | Compute ESSL/ISA/驱动语义闭合 | 原编译器字节和反射绑定；flow、barrier/mask、资源索引及实际 ioctl 路径覆盖 E31-01–05；接口和能力查询与实测一致 | P07–P12 | 待闭合 |

P07 的首个 increment recipe 只是该 PR 的第一条实现/验证路径，不能以
它替代其余 atomic-counter 操作或 H7 资源与 coherence 门槛。

## H8 stage、uniform、texture 与 sample 合同

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P14 | 原 GS→FS ABI7 与 GS/TF 同 host 语料 | 原 ESSL GS、非零16-slot varying、ABI7 FS、sample/mask、4 MRT；TF在clip/flat前的原始输出及render规则、discard/fault/cancel/ACK/无reset复用精确验证 | P03、P04 | 已有组件/保护源码，组合语料待补 |
| P15 | TCS/TES checked-uniform RTL 与版本化 ABI | 明确 stage4/5 root/provider，实际 CU scalar/vector load与writeback、仲裁/drain；block0/11/default31、16KiB/1024 defaults和错误stage/version/map、fault/cancel/reset通过；冻结旧GUR/GNT合同 | P03；新合同重做P04 | 待实现 |
| P16 | Protected TCS/TES program、uniform、GNQ/GNT admission | 原 GEM code/default/UBO进入正确 RX/R-NX私有视图；真实可达性、writer冲突、immutable snapshot、引用、三domain执行、retirement/cleanup闭合 | P15 | 待实现 |
| P17 | 原 Mesa TCS/TES lowering 与 API72 无损映射 | binding0..71→每stage至多12 blocks→独立私有表；真实ESSL/反射/GEM/GUR/GUB闭合，binding71、rebind、同GEM不同view、跨stage复用及60个graphics引用正确 | P15、P16 | 待实现 |
| P18 | Tessellation domain/spacing/primitive generation 完整语义 | isoline/triangle/quad、所需spacing/winding/边界、不变性、amplification/overflow、primitive bounds与实际protected stages组合；独立几何/图像和fault/order oracle | P15–P17 | 待闭合 |
| P19 | 96 combined texture namespace 与六stage checked sampler | 各stage16 units及全局accounting、stage-local无损映射、TCS/TES真实采样返回；原resource来源、immutable view、writer/fault/drain/cleanup完整 | P15–P17 | 待实现 |
| P20 | Sample-rate、显式插值与 shader sample I/O | 实际1x/2x/4x、center/sample/centroid/offset、sampleID/position/mask、alpha-to-coverage/discard/depth/MRT/resolve；原shader输入输出及完整host结果独立检查 | P05、P14、P18 | 待闭合 |
| P21 | Advanced blend、indexed MRT 与对应原API状态 | 所需方程、独立enable/factor/equation/mask、sRGB、barrier、float附件与clear/visibility组合；原状态提交到native tile结果及guard一致 | P05、P20 | 待闭合 |
| P22 | GLES3.2 texture builtins 与特殊 target 的整合 | 动态resource array、gather/offset、buffer texture、border、cube-array、stencil、multisample-array；原编译/driver/ISA/各stage实际取样和limits均验证 | P19 | 待闭合 |
| P23 | ASTC及压缩/颜色格式完整原路径 | 全规定ASTC LDR footprints/modes、ETC2/EAC/sRGB等目录与decoder/采样绑定；独立格式oracle、非法块/边界/fault及目标host组合证据 | P19、P22 | 待闭合 |

72 UBO binding、每 stage 12 blocks、graphics跨stage60个引用与96个combined
texture units 是不同的合同，不能靠扩大同一个数组或复用可信 producer 关闭。

## H6 基础覆盖与生产软件接口

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P24 | Vertex/primitive/raster 完整输入语义 | attribute/index/restart/instance/divisor/baseVertex、topology、clip/viewport、point/line/facing/polygon offset；原提交、边界/fault与独立覆盖/图像oracle，包括line-width查询 | P03、P04 | 待闭合 |
| P25 | Graphics资源极限与stage接口 | 16 attributes、4 draw buffers/attachments、uniform/default/varying/linked blocks、2D/cube2048、array256/3D256等逐项可发现限制、全容量组合、alias/末地址/权限验证 | P14、P17、P19 | 待闭合 |
| P26 | Shader numeric、flow、helper/derivative 与精确算术 | 真实编译/ISA/validator 的数值、FMA/precise、控制流/终止、helper/discard、derivative、插值/接口语义；独立数值oracle和故障/ordering检查；ISA变化同步独立LLVM仓库 | P05、P14、P17 | 待闭合 |
| P27 | GLES3.0 texture/sampler/mip 全路径 | 原描述符/绑定、维度/layer/mip、wrap/LOD/filter/shadow、格式、size/projective、上传/转移与实际各stage采样；来源、fault和visibility均闭合 | P04、P19、P23 | 待闭合 |
| P28 | Framebuffer状态、copy/transfer 与完整格式覆盖 | typed/float附件、MRT、depth/stencil、mask/dither、MSAA、scissor/clear、缩放/格式/region copies和pixel layout组合；实际结果/尾部/alias/可见性验证 | P06、P20、P21、P27 | 待闭合 |
| P29 | 全资源 robustness、reset 与对象生命周期 | 各owner越界/权限/上下文隔离、原GEM删除/重绑、fault来源、quarantine、reset/cancel/drain及健康复用；不能提前释放活动输入或伪造ACK | P11、P12、P16、P28 | 待闭合 |
| P30 | Visibility/primitive queries 与通用 fence 合同 | 实际query结果、scope/ordering/overflow、completion/fence跨资源/上下文、timeout/error/reset与对象寿命；原API路径及完整host证据 | P11、P29 | 待闭合 |
| P31 | Debug/object API 与 reset/robust 状态交付 | message过滤、标签/group/callback、对象诊断、readn边界与reset cause交付；真实API调用到原driver状态及顺序/溢出/生命周期检查 | P29、P30 | 待补齐 |
| P32 | 编译器/驱动/API 联合入口和真实能力报告 | 原应用/GL/ESSL入口覆盖前述功能，资源/版本/扩展查询只报告已满足的合同；缺callback/lowering/validator接线逐项补齐并用真实调用验收 | P13–P31 | 待闭合 |

各功能 PR 应包含其实际 compiler/driver/API 接线与限定范围测试。P32负责
联合入口和报告的一致性，不是用一个总开关绕过前面未实现或未验证的功能。
本GPU仓库的PR/保护规则不施加到独立LLVM、Yosys或FlashSim仓库。

## 性能、物理与最终验收

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P33 | Native texture吞吐与memory ownership资格 | 每cluster长resident conflict-free bilinear RGBA8流达到约定1结果/clock；分别测trilinear、miss、bank conflict、dependent sample与translation/fault，记录实际服务率 | P11、P19、P27 | 已有组件证据，联合验收待执行 |
| P34 | Pixel吞吐、hazard 与attachment locality资格 | 每engine约定4个RGBA8+D24S8 sample updates/clock；blend/depth/stencil模式、same-sample冲突、early/late等价；clear/discard无old读、中间不外写、最终store/resolve正确 | P20、P21、P28 | 已有组件证据，联合验收待执行 |
| P35 | 1→2 CU固定/比例资源 scaling | 同工作、完整CU容量、固定非CU资源的长shader-bound测试；比例资源与真实固定带宽另测，counter解释瓶颈；4CU只作可选扩展 | P26、P33、P34 | 历史结果范围有限，目标联合验收待执行 |
| P36 | 完整host映射时序/面积与匹配频率 | 当前政策下先完成whole-host映射综合，包含I/O约束、SRAM假设、实现阶段、完整配置与源码匹配；按实际达标频率给预算，组件PPA或1GHz假设不可替代最终所需时序证据 | P15–P35的最终合同/实现 | 待执行/闭合 |
| P37 | 六场景2CU VGA60产品资格 | 同一最终host、640×480；六场景完整bin/geometry/render/store/resolve，全部独立图像/sample/guard检查；匹配P36频率/内存假设，逐场满足60FPS预算 | P05–P36 | 待执行/闭合 |
| P38 | H0–H8联合验收与正式覆盖矩阵 | requirement→生产入口→测试→精确factory/source→结果逐项可追溯；功能/容量/故障/ordering/API/rates/六场景/匹配时序全部过关；任一缺项保持未完成 | P01–P37 | 待最终验收 |

六场景固定为 `desktop-composition`、`textured-3d`、`opaque-overdraw`、
`transparent-layers`、`small-triangles`、`msaa-4x`。预算为
`floor(qualifiedClockHz / 60)`，包含整帧服务周期；模拟墙钟速度不等于GPU FPS。
当前综合筛选政策不自动扩大为新增place-and-route任务，也不把综合阶段
报告写成已经完成后续物理签核。

## 对正式需求的覆盖

依据：[主alignment目标](MOBILE_GPU_HARDWARE_ALIGNMENT.md)、
[capability matrix](MOBILE_GPU_CAPABILITY_MATRIX.md)、
[resource limits](MOBILE_GPU_RESOURCE_LIMITS.md)、
[GLES3.2 delta](GLES32_HARDWARE_GAPS.md)。这些原始表仍保留其限定范围；
P38须检查规范条款/操作/格式/limit覆盖，不能仅凭汇总表无红项结束目标。

| 正式范围 | 工作包 |
|---|---|
| V01–V04、G01–G04；V05/V06/V07 | P24；P25/P26；P14 |
| S01–S04 | P20、P26、P32 |
| T01–T09、T12；T10/T11 | P19、P22、P23、P27；P11、P29、P33 |
| F01–F08 | P06、P20、P21、P28、P34 |
| Q01/Q02；M01–M03 | P30；P11、P12、P16、P29、P30 |
| E31-01–E31-05 | P07–P13 |
| E32-01；E32-02；E32-03 | P14；P15–P17；P18 |
| E32-04a/c/u；04b；04d | P21/P28；P28；P31 |
| E32-04e/f/k | P19/P22/P26；P26；P17/P25/P26 |
| E32-04g/o/p/q/r/s；04h | P22；P23 |
| E32-04i/j；04l/m/n | P18/P08/P10；P20 |
| E32-04t/v/w | P24；P12/P29/P31；P24/P32 |
| H0–H5的保留能力/联合回归；H6/H7/H8退出门槛 | P04–P06、P24–P35、P38；P36–P38及各功能行 |

状态更新以实际PR和验收结果为准。每次交付同时给出“本次合入编号、
已验收完成数/当前基线总数、下一编号、尚缺的目标门槛”，不以打开多少
分支或完成多少CPU测试代替整体进度。
