# GPU alignment PR 工作清单

本文件是剩余 alignment 工作的执行账本。目标保持为完整的 H6–H8 功能、
资源、编译器/驱动/API 接口和产品验收：**2 CUs、640×480、60 FPS**。
保留支持的 16-lane/eight-warp、SRAM 和资源容量；720p、4 CU 是可选扩展。
历史组件测试、CPU 协议测试和旧模型结果分别保留其范围。

## 当前总览

- 最近已合入：P02 / [PR #820](https://github.com/OpenGPGPU/OpenGPU/pull/820)，
  合入提交 `dd439333e515d96f1d45ec487a009e82aa002b9c`；完整主机检查、64 项
  局部 CPU/协议检查和受保护 `ci-ok` 通过。Scala 编译/unit/affected 按变更
  选择跳过，不能记作本次 Scala 执行。P01 / #819 也已完成源码交付验收。
- 当前基线为 **40 个 PR 工作包**：源码交付 3 项，功能/资源/API 闭合 31 项，
  性能与最终验收 6 项。验收完成并合入 **2/40**，剩余 **38 项**。
  清单显式包含 P39（原 VS→patch）和 P40（完整动态 TES patch read），
  不再把两者隐含在已经存在的组件结果中；原编号保持不变。
- P03 是当前交付项；后续功能 PR 在前项合入后开放。P01/P02 的临时工作树
  与分支已按合入 head、清洁状态和活动消费者核对后清理。
- P07 的九条 recipe、六组原 ESSL 已覆盖 increment/read/decrement/动态索引的
  真实 Mesa CPU/capture、严格输入与 C++ parser；实际 DUT 与资源共存仍未验收。
- P15、P16、P17、P39 已有下表列出的限定范围源码与检查，均未完成目标验收。
  当前112仍非完整资源资格，旧96模型的活动生成也不关闭 P04。

这里的 2/40 只统计本次列出的剩余工作，不是整个项目的历史完成率。
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
8. `Scala regression`（`.github/workflows/scala_regression.yml`）因测试量过大而
   保持停用，禁止启用或触发。使用现有受保护 PR `Scala CI`、聚焦本地测试
   和明确范围的资格检查；没有执行的 Scala/RTL/DUT 验证仍记待完成。
9. 发现新的必需缺口时，在清单中公开新增或拆分编号、说明依赖，并显式
   更新总数；不通过缩小既定验收条件使任务变绿。

## 正式入口与分支用途

GPU 的正式交付入口是受保护的 [`main`](https://github.com/OpenGPGPU/OpenGPU/tree/main)。
下列开发分支保留尚未交付的源码，各有固定用途；不能因为其中已有更多代码，
就把该分支当作正式版本或把对应目标计为完成。

| 仓库 / 分支 | 用途与交付方式 |
|---|---|
| GPU `main` | 已通过受保护 CI 并合入的正式源码；最近基线为 #820 |
| GPU `codex/alignment-delivery-c2` | P03 当前交付分支；从 #820 的实际 main 重放后39项，只通过 PR 进入 main |
| GPU `codex/native-sample-dut-bridge` | 保留 P02/P03 的原开发源码，按本清单分批交付；不另外开放并行 PR |
| GPU `codex/owner-counter-live` | P07 的隔离实现分支；CPU 操作语料已扩展，实际 DUT/共存验收待完成 |
| GPU `codex/p15-tess-checked-uniform` | P15 checked-uniform/版本化 ABI 源码；后续交付只重放其三项实现逻辑提交 |
| GPU `codex/p16-protected-tess` | P16 私有原 GEM/RX/validator 与 protected submit 实现；完整提交/执行仍未闭合 |
| GPU `codex/p17-tess-mesa` | P17 两项原 Mesa frontend/emitter/私有资源回调提交；CPU 通过不等于原 CU 执行 |
| GPU `codex/p39-vertex-patch-source` | P39 原 draw slice 与 VS→patch ACK/seal 源码；限定八文件增量编译和 publisher CHIRRTL通过，完整源码构建/RTL/CU待执行，不启用正式入口 |
| GPU `codex/p40-dynamic-tes-patch` | P40 动态TES typed read/CU/provider实现分支；源码与测试在补齐，旧61词transport不算完整读取资格 |
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
| P01 | GS、layered/sample、104/112 protected factory 与 compute 语料的源码交付 | 保留 main 修复和原逻辑提交；完整 host regression、GNC5/GNJ2 生产初始化检查、Scala/相关 CI 通过 | #818 | [#819](https://github.com/OpenGPGPU/OpenGPU/pull/819)；源码交付验收完成并已合入 |
| P02 | 原 Mesa/native96 与 sample ABI6/7 编译、保护提交、资格及生命周期源码交付 | 单独重放前45个逻辑补丁和必要 merge-resolution；源码并集、链接/协议/拒绝检查与 CI 通过 | P01 | [#820](https://github.com/OpenGPGPU/OpenGPU/pull/820)；原45项与必要修复/政策文档的并集、完整 host 脚本、64项局部CPU/协议及相关ASan/UBSan检查通过；源码交付验收完成并已合入 |
| P03 | tess sample/image 路由、ordinary112 双 owner oracle/controller、LIVE 反例及独立112 blit 入口源码交付 | 交付后39个补丁；保留 cleanup 退出竞态修复、真实 C 拒绝检查、43包 blit 守卫；与完整开发树逐路径核对并通过 CI | P02 | 当前交付；39项原逻辑提交的逐路径并集、完整host脚本、107项局部CPU/协议与相关ASan/UBSan检查通过；受保护CI/合入待完成 |

P02/P03 的分界按现有逻辑提交和依赖确定，保持代码可审查。它们已有的
CPU、受限 Linux 构建和局部 RTL 结果只证明各自记录的范围。

P02 从 #819 的准确 main 重放，保留原作者、日期、逻辑消息与 `-x`，并保留
主线 GSS/GNC、JDK11、GS/TF 和生命周期修复。完整 host 脚本通过；当前环境
没有 Mesa 依赖的项目按脚本明确跳过，不能把它们记作本次实际 Mesa 执行。
原分支记录的受限 Mesa CPU 结果仍只有各自的源码/运行范围。复现主机检查：

```sh
PYTHONDONTWRITEBYTECODE=1 bash scripts/test_scripts.sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_submit_bridge.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_owner.py
git diff --check
```

这些命令不生成 RTL，不证明新完整 factory、实际 DUT 或 VGA60。

P03 保留原后39项逻辑提交；对原完整开发树逐路径核对，差异只保留主线修复、
政策/计划文档和既有脚本顺序。完整 host 脚本及13个聚焦脚本的107项测试通过；
Mesa 缺失项目仍明确跳过，112 blit 的 C++ 声明型端口检查也不是实际 DUT。
额外聚焦复现命令：

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_submit_bridge.py
python3 -B scripts/test_framebuffer_blit_host.py
```

## 完整 host、H7 执行与内存合同

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P04 | 新完整目标 factory 的 RTL、模型和执行器资格 | 新鲜生成；全容量、assertions、源码/工具/分片/二进制绑定与原 probe 一致；具备可复现的实际执行入口。后续合同变化须重做此绑定 | P01–P03 | 待执行；当前旧96生成不关闭本项 |
| P05 | 原 Mesa graphics/sample 的完整 host 双 owner 执行 | 实际 shader、原 GEM/Sv32、同 RAM/无中间 reset；两次结果由独立图像/GNY2/guard oracle 验证；实际 retirement、PTE/shootdown/context cleanup ACK 全部成功 | P04 | 代码/CPU证据已有，实际 DUT 待执行 |
| P06 | 43个原调用者 framebuffer blit 的目标 host 执行 | 原43包及 source SHA 不变；精确字节/VM guard、source fault、延迟 R/B reset/drain、健康 ID 复用在目标 DUT 上通过 | P03、P04 | 112入口已实现，实际 DUT 待执行 |
| P07 | 原 GEM atomic-counter live 提交与完整操作语料 | 实际 Mesa→生产 ioctl→目标 DUT；inc/read/dec、动态索引及资源共存；独立返回集合/最终 counter/全字节 guard，保留真实 owner 至 cleanup ACK | P04 | 九条 recipe/六组原 ESSL 的 inc/read/dec/动态索引 CPU/capture 检查通过；实际 DUT/共存待完成 |
| P08 | Typed image load/store/atomic live 提交 | 原 image view、format/pitch/layer/权限与精确 writable view；实际 shader 结果、边界、fault/cancel/reset 及独立 oracle 通过 | P04、P07 | 待补齐 |
| P09 | Indirect compute 原始 source 与执行合同 | 专用 opcode14 capture；GPU产生的三维 grid、依赖 fence/snapshot、零工作、极限/越界、失败与复用均通过，不靠 opcode 白名单 | P04、P07 | 待补齐 |
| P10 | Storage/global/image atomic 完整操作与竞争 | 全规定操作、CAS 精确旧值、竞争与重排、延迟 ACK、reservation、只读/越界、故障后健康复用；实际完整 host 验证 | P07、P08 | 待闭合 |
| P11 | 多 owner、多命令和跨域 coherence/barrier | 同 RAM、无 reset，GPU producer 与 warmed consumer；SSBO/image/texture/attachment/DMA/counter/shared 的 publication、barrier、fence 和 cleanup 矩阵通过 | P05–P10 | 待补齐 |
| P12 | Compute 资源共存、极限与 robustness | 保留128本地线程/16KiB共享及4SSBO、4image、8counter、32texture、12UBO/default；同时使用、末地址、namespace/alias/permission、cancel/reset 均有完整 host 证据 | P07–P11 | 待闭合 |
| P13 | Compute ESSL/ISA/驱动语义闭合 | 原编译器字节和反射绑定；flow、barrier/mask、资源索引及实际 ioctl 路径覆盖 E31-01–05；接口和能力查询与实测一致 | P07–P12 | 待闭合 |

P07 的 CPU 操作语料已有扩展，但不能替代实际 DUT、原提交链、资源共存
或 H7 coherence 门槛。

## H8 stage、uniform、texture 与 sample 合同

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P14 | 原 GS→FS ABI7 与 GS/TF 同 host 语料 | 原 ESSL GS、非零16-slot varying、ABI7 FS、sample/mask、4 MRT；TF在clip/flat前的原始输出及render规则、discard/fault/cancel/ACK/无reset复用精确验证 | P03、P04 | 已有组件/保护源码，组合语料待补 |
| P15 | TCS/TES checked-uniform RTL 与版本化 ABI | 明确 stage4/5 root/provider，实际 CU scalar/vector load与writeback、仲裁/drain；block0/11/default31、16KiB/1024 defaults和错误stage/version/map、fault/cancel/reset通过；冻结旧GUR/GNT合同 | P03；新合同重做P04 | 三项实现提交已有；GUR3/GUB3、GNT4/5/6 与 CPU ABI/选择检查通过；默认关闭，Scala typecheck/RTL/CU 待执行 |
| P16 | Protected TCS/TES program、uniform、GNQ/GNT admission | 原 GEM code/default/UBO进入正确 RX/R-NX私有视图；真实可达性、writer冲突、immutable snapshot、引用、owned patch RAM/level 初始化隔离、三domain执行、retirement/cleanup闭合；只消费实际验证的硬件版本/容量/路由 | P15、P39、P40 | 原 GEM mapper、独立严格 validator/RX snapshot、2048 耦合容量、共享标量分歧修复及新三域typed BUILD的CPU/MMU/负控通过；Linux typed submit/退役验证在补齐；usable ioctl/probe 未注册，实际 protected stage 执行待完成 |
| P17 | 原 Mesa TCS/TES lowering 与 API72 无损映射 | binding0..71→每stage至多12 blocks→独立私有表；真实ESSL/反射/GEM/GUR/GUB闭合，binding71、rebind、同GEM不同view、跨stage复用及60个graphics引用正确 | P15；与P16/P39/P40联合执行 | 两项实现提交已有；实际 Mesa 三组原 ESSL、五stage60-block 反射、原 GEM 资源生命周期及严格 RX/MMU ASan/UBSan CPU 检查通过；protected submit 接线/实际 CU/API 暴露待完成 |
| P18 | Tessellation domain/spacing/primitive generation 完整语义 | isoline/triangle/quad、所需spacing/winding/边界、不变性、amplification/overflow、primitive bounds与实际protected stages组合；独立几何/图像和fault/order oracle | P15–P17、P39、P40 | 待闭合 |
| P19 | 96 combined texture namespace 与六stage checked sampler | 各stage16 units及全局accounting、stage-local无损映射、TCS/TES真实采样返回；原resource来源、immutable view、writer/fault/drain/cleanup完整 | P15–P17 | 待实现 |
| P20 | Sample-rate、显式插值与 shader sample I/O | 实际1x/2x/4x、center/sample/centroid/offset、sampleID/position/mask、alpha-to-coverage/discard/depth/MRT/resolve；原shader输入输出及完整host结果独立检查 | P05、P14、P18 | 待闭合 |
| P21 | Advanced blend、indexed MRT 与对应原API状态 | 所需方程、独立enable/factor/equation/mask、sRGB、barrier、float附件与clear/visibility组合；原状态提交到native tile结果及guard一致 | P05、P20 | 待闭合 |
| P22 | GLES3.2 texture builtins 与特殊 target 的整合 | 动态resource array、gather/offset、buffer texture、border、cube-array、stencil、multisample-array；原编译/driver/ISA/各stage实际取样和limits均验证 | P19 | 待闭合 |
| P23 | ASTC及压缩/颜色格式完整原路径 | 全规定ASTC LDR footprints/modes、ETC2/EAC/sRGB等目录与decoder/采样绑定；独立格式oracle、非法块/边界/fault及目标host组合证据 | P19、P22 | 待闭合 |
| P39 | 原 VS 输入/实例/index/restart→owned patch 的生产链 | 保留原 draw、实际原 ESSL/Mesa VS 执行与 GPU patch assembly；原 attribute/index/instance/divisor、linked component、同 patch identity/来源正确；输入逐字 ACK 后再 seal；原 code/resource authority、zero-init 隔离、fault/cancel/drain/retirement、三域实际组合验证，不用 CPU 预制 patch 关闭 | P15、P17；与P16联合验证，完整模型重做P04 | 可选原 draw slice、实际 VS wrapper 与 ACK/seal publisher 已有源码；145项选择检查、八文件Chisel plugin增量编译及1/16 varying publisher CHIRRTL通过（只读冻结依赖、128MiB heap）；wrapper elaboration未完成，完整源码构建/RTL/CU/native三域执行仍待 |
| P40 | TES 完整动态 patch read service、ISA 与原编译器 lowering | 在支持的32×64/120/2048容量内动态读取任意合法 control vertex/component、per-patch 与 tess levels；TES coords/原实例语义、实际 CU返回/writeback、越界/stage/version、仲裁及fault/cancel/drain；原 Mesa 动态索引程序与三域 protected 组合验证，ISA变化同步独立LLVM仓库 | P15、P17；与P16联合验证，完整模型重做P04 | 独立默认关闭的vector typed read/CU/provider源码在实现，验证待完成；现有61静态选择加3坐标/单点 transport不是完整patch读取服务 |

P39/P40 的完整边界见 [执行缺口](TESSELLATION_EXECUTION_GAPS.md)。P16/P17
可先实现独立源码，但各项的实际联合验收不能互相借用未完成的结果。
现有 patch SRAM/levels 的未写值为 undefined，不证明跨 owner 隔离；启用
protected 路径前必须实际完成初始化隔离或严格的初始化证明。

72 UBO binding、每 stage 12 blocks、graphics跨stage60个引用与96个combined
texture units 是不同的合同，不能靠扩大同一个数组或复用可信 producer 关闭。

## H6 基础覆盖与生产软件接口

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P24 | Vertex/primitive/raster 完整输入语义 | attribute/index/restart/instance/divisor/baseVertex、topology、clip/viewport、point/line/facing/polygon offset；原提交、边界/fault与独立覆盖/图像oracle，包括line-width查询 | P03、P04 | 待闭合 |
| P25 | Graphics资源极限与stage接口 | 16 attributes、4 draw buffers/attachments、uniform/default/varying/linked blocks、2D/cube2048、array256/3D256等逐项可发现限制、全容量组合、alias/末地址/权限验证 | P14、P17、P19、P39、P40 | 待闭合 |
| P26 | Shader numeric、flow、helper/derivative 与精确算术 | 真实编译/ISA/validator 的数值、FMA/precise、控制流/终止、helper/discard、derivative、插值/接口语义；独立数值oracle和故障/ordering检查；ISA变化同步独立LLVM仓库 | P05、P14、P17、P39、P40 | 待闭合 |
| P27 | GLES3.0 texture/sampler/mip 全路径 | 原描述符/绑定、维度/layer/mip、wrap/LOD/filter/shadow、格式、size/projective、上传/转移与实际各stage采样；来源、fault和visibility均闭合 | P04、P19、P23 | 待闭合 |
| P28 | Framebuffer状态、copy/transfer 与完整格式覆盖 | typed/float附件、MRT、depth/stencil、mask/dither、MSAA、scissor/clear、缩放/格式/region copies和pixel layout组合；实际结果/尾部/alias/可见性验证 | P06、P20、P21、P27 | 待闭合 |
| P29 | 全资源 robustness、reset 与对象生命周期 | 各owner越界/权限/上下文隔离、原GEM删除/重绑、fault来源、quarantine、reset/cancel/drain及健康复用；不能提前释放活动输入或伪造ACK | P11、P12、P16、P28 | 待闭合 |
| P30 | Visibility/primitive queries 与通用 fence 合同 | 实际query结果、scope/ordering/overflow、completion/fence跨资源/上下文、timeout/error/reset与对象寿命；原API路径及完整host证据 | P11、P29 | 待闭合 |
| P31 | Debug/object API 与 reset/robust 状态交付 | message过滤、标签/group/callback、对象诊断、readn边界与reset cause交付；真实API调用到原driver状态及顺序/溢出/生命周期检查 | P29、P30 | 待补齐 |
| P32 | 编译器/驱动/API 联合入口和真实能力报告 | 原应用/GL/ESSL入口覆盖前述功能，资源/版本/扩展查询只报告已满足的合同；缺callback/lowering/validator接线逐项补齐并用真实调用验收 | P13–P31、P39、P40 | 待闭合 |

各功能 PR 应包含其实际 compiler/driver/API 接线与限定范围测试。P32负责
联合入口和报告的一致性，不是用一个总开关绕过前面未实现或未验证的功能。
本GPU仓库的PR/保护规则不施加到独立LLVM、Yosys或FlashSim仓库。

## 性能、物理与最终验收

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P33 | Native texture吞吐与memory ownership资格 | 每cluster长resident conflict-free bilinear RGBA8流达到约定1结果/clock；分别测trilinear、miss、bank conflict、dependent sample与translation/fault，记录实际服务率 | P11、P19、P27 | 已有组件证据，联合验收待执行 |
| P34 | Pixel吞吐、hazard 与attachment locality资格 | 每engine约定4个RGBA8+D24S8 sample updates/clock；blend/depth/stencil模式、same-sample冲突、early/late等价；clear/discard无old读、中间不外写、最终store/resolve正确 | P20、P21、P28 | 已有组件证据，联合验收待执行 |
| P35 | 1→2 CU固定/比例资源 scaling | 同工作、完整CU容量、固定非CU资源的长shader-bound测试；比例资源与真实固定带宽另测，counter解释瓶颈；4CU只作可选扩展 | P26、P33、P34 | 历史结果范围有限，目标联合验收待执行 |
| P36 | 完整host映射时序/面积与匹配频率 | 当前政策下先完成whole-host映射综合，包含I/O约束、SRAM假设、实现阶段、完整配置与源码匹配；按实际达标频率给预算，组件PPA或1GHz假设不可替代最终所需时序证据 | P15–P35、P39/P40的最终合同/实现 | 待执行/闭合 |
| P37 | 六场景2CU VGA60产品资格 | 同一最终host、640×480；六场景完整bin/geometry/render/store/resolve，全部独立图像/sample/guard检查；匹配P36频率/内存假设，逐场满足60FPS预算 | P05–P36、P39、P40 | 待执行/闭合 |
| P38 | H0–H8联合验收与正式覆盖矩阵 | requirement→生产入口→测试→精确factory/source→结果逐项可追溯；功能/容量/故障/ordering/API/rates/六场景/匹配时序全部过关；任一缺项保持未完成 | P01–P37、P39、P40 | 待最终验收 |

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
| E32-01；E32-02；E32-03 | P14；P15–P17/P39/P40；P18 |
| E32-04a/c/u；04b；04d | P21/P28；P28；P31 |
| E32-04e/f/k | P19/P22/P26；P26；P17/P25/P26 |
| E32-04g/o/p/q/r/s；04h | P22；P23 |
| E32-04i/j；04l/m/n | P18/P08/P10；P20 |
| E32-04t/v/w | P24；P12/P29/P31；P24/P32 |
| H0–H5的保留能力/联合回归；H6/H7/H8退出门槛 | P04–P06、P24–P35、P38；P36–P38及各功能行 |

状态更新以实际PR和验收结果为准。每次交付同时给出“本次合入编号、
已验收完成数/当前基线总数、下一编号、尚缺的目标门槛”，不以打开多少
分支或完成多少CPU测试代替整体进度。
