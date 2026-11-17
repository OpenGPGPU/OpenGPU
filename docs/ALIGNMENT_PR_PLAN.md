# GPU alignment PR 工作清单

本文件是剩余 alignment 工作的执行账本。目标保持为完整的 H6–H8 功能、
资源、编译器/驱动/API 接口和产品验收：**2 CUs、640×480、60 FPS**。
保留支持的 16-lane/eight-warp、SRAM 和资源容量；720p、4 CU 是可选扩展。
历史组件测试、CPU 协议测试和旧模型结果分别保留其范围。

## 当前总览

- P04完整factory、模型与原执行器的绑定验收已通过，
  [#847](https://github.com/OpenGPGPU/OpenGPU/pull/847)受保护合入后计为完成。
  源码阶段[#845](https://github.com/OpenGPGPU/OpenGPU/pull/845)和
  [#846](https://github.com/OpenGPGPU/OpenGPU/pull/846)已合入；788生产源/1131测试源
  新鲜编译、完整sample RTL与开启assertions的Verilator模型、四个原执行器构建和
  三个实际reset/MMIO probe均通过。每个probe读回112字，辅助寄存器分别为5/5/10项，
  原独立validator及idle/IRQ/无外部内存工作检查通过；
  [最终结果、边界与复现](OWNER_FULL_FACTORY_REPRODUCTION.md)。
  后续stage、资源或ABI变化仍须重新绑定；shader/API、43个blit包、资源共存和
  2-CU VGA60保留各自门槛。同时只开放一份alignment PR。
- P05原Mesa22.3.6 ABI1 graphics/sample双owner在P04完整host上的实际执行通过：
  两个原shader/GEM/Sv32 owner共用RAM、context且只reset一次；逐次和成对的独立
  图像/GNY2/精确byte guard、实际retirement及PTE/shootdown/context cleanup ACK全部通过。
  原LIVE producer唯一成功标记、执行器退出0和消费者清理均确认。
  [最终结果、4×4/1-sample范围与复现](NATIVE_SAMPLE_GRAPHICS_OWNER.md#actual-complete-host-execution-p05)。
- P06的43个原调用者framebuffer blit包在完整112字sample host实际通过：
  原shader/kernarg/view/map及source SHA保持不变；逐包退休、独立字节、VM/read-only guard、
  source fault、延迟R/B reset/drain、partial-cancel字节和健康ID复用全部通过。
  执行器和完整qualifier退出0、消费者清理及执行后的源码/工具/模型/输入复查均确认。
  [最终结果、bounded VM与原小尺寸语料范围及复现](NATIVE_FRAMEBUFFER_BLIT_SAMPLE_OWNER.md#actual-complete-host-execution-p06)。
- ordinary PrimitiveID ABI8/9源码交付为
  [#837](https://github.com/OpenGPGPU/OpenGPU/pull/837)，merge `1a03cfd93`、准确head `b7a0c5d1c`。
  正常 CI [43820053475](https://github.com/OpenGPGPU/OpenGPU/actions/runs/43820053475)
  的49个job（含受保护`ci-ok`）全部成功并受保护rebase合入。准确交付树和本地分支已按head、
  清洁状态、进程、打开文件及跨树引用检查清理；远端head已自动删除。
  [ABI8/9最终结果与边界](FRAGMENT_PRIMITIVE_INPUT_ABI.md)保持原范围：stage打包的CU端是协议peer，
  四项full-CU FS shader检查仍待执行。#836独立语言binding的实际单CU结果保留
  [原范围](TESSELLATION_LANGUAGE_BINDING.md)，不代表原Mesa/完整父级/两颗CU执行。
  本阶段交付原始input-patch ID的三域生产接线：788生产源/1130测试源全新编译、十项组件RTL、
  三域完整容量父级CIRCT、两项精确源负控、28类的31个旧完整/辅助构造签名和167项selector检查通过；
  [最终结果、限制与复现](TESSELLATION_INPUT_PATCH_ROUTE_DELIVERY.md)。组件资格生成物已清理。
  三项逻辑提交重放到#837时完整树字节一致，随后只更新本清单；生产源、测试、脚本及构建输入未改。
  内部五阶段host的接口/组件结果保留原范围；joint-frame/range、原shader/三域protected/API、
  完整资源和2-CU VGA60仍待其各自目标验收。阶段源码合入不自动增加闭环计数。
  P01 / #819、P02 / #820、P03 / #821 已完成各自的源码交付验收。
- 当前基线为 **40 个 PR 工作包**：源码交付 3 项，功能/资源/API 闭合 31 项，
  性能与最终验收 6 项。本交付受保护合入后，验收完成并合入 **7/40**，剩余 **33 项**。
  清单显式包含 P39（原 VS→patch）和 P40（完整动态 TES patch read），
  不再把两者隐含在已经存在的组件结果中；原编号保持不变。
- P15 源码已合入；三域客户端在实际报告消费后清理取消/故障状态，六项定向
  RTL 和两项原代码负控通过。正常 CI 中 4-lane/two-warp 实际 CU isoline client、system input
  和 viewport 检查也通过。全容量、三域实际 CU、完整资源与产品验收仍待完成。
  #822 的临时交付工作树和本地分支已按准确 head、清洁状态、进程、打开文件
  及跨工作树消费者检查清理，远端 head 已自动删除。
- P16 源码交付为 [PR #823](https://github.com/OpenGPGPU/OpenGPU/pull/823)
  已合入正式 `main`；准确交付工作树及本地分支经清洁、进程、打开文件和
  跨工作树消费者检查后已清理，远端 head 已自动删除：
  私有快照、程序验证、typed BUILD、关闭的内部 admission 与 GVS1/GNT7–9 合同。
  保留原12个逻辑提交；GVS 仍是 root-only，BUILD 仍用 GNT4/5/6 和静态 TES v1。
  新 GNT7–9 联合 BUILD、reader/range/parent、实际 CU 和 protected 执行尚未闭合；
  不注册可用 ioctl，不启用 capability 或生产 getter。本批源码合入不关闭 P16。
- P39 源码已通过 #824 合入；交付从 #823 重放15个逻辑源码提交，
  范围为原 draw slice、ACK/seal publisher、zero-init、range retirement 和 GVS1 reader；
  准确交付源的新鲜9个production/2个helper/5个Spec编译、10项组件RTL和152项selector检查通过；
  [交付范围、限制与复现](P39_SOURCE_DELIVERY.md)。准确 head 的临时交付树、
  本地分支经清洁、进程、打开文件和跨工作树消费者检查后已清理，远端已自动删除。
  原 VS 实际执行、联合 native/三域路径仍是 P39 的目标验收条件。
- P16 原 draw catalog/invocation range 捕获、内部语言程序 v3、程序快照
  权限边界与 FS8/9 私有程序验证的六个逻辑提交已通过 #825 合入。
  [交付范围、结果与限制](P16_INVOCATION_SOURCE_DELIVERY.md)：准确交付源完整 host
  检查通过（724.24s，768MiB aggregate 守卫，152 pytest、21 strict producer，
  六项环境 skip 保留）。准确 head 的临时交付树、本地分支经清洁、进程、打开
  文件和跨工作树消费者检查后已清理，远端已自动删除。
- P17 原 TCS/TES、动态 TES、语言 input-patch ID、ordinary FS8/9 与私有
  program/source/draw 六个逻辑源码提交已通过 #826 合入。
  [交付范围与复现](P17_COMPILER_SOURCE_DELIVERY.md)：准确交付源完整 host
  检查通过（748.35s，768MiB aggregate 守卫，152 pytest/21 strict producer，
  六项环境 skip）；三组真实 Mesa CPU 检查和独立 P16 程序字节消费通过。
- P17 同一原链接 VS/TCS/TES/FS 编译和原52引用 source lease 已通过
  [PR #827](https://github.com/OpenGPGPU/OpenGPU/pull/827) 合入，保留原逻辑提交
  `9ed94be7b`、`9fde939af`、`53103920c`。
  [范围、实际 CPU 结果与限制](NATIVE_JOINT_COMPILER_SOURCE.md)：三组原 Mesa
  程序、45 TU 全新编译、旧字节兼容与两项源码负控通过；正式 main 独立
  validator/RX/MMU 消费12个原程序通过；mapped 地址宽度乘法已修正，
  16-lane/eight-warp 实际 Decoder/IntegerAlu 正例和恢复旧错误的精确负控通过。
  原52资源完整GUR图与 joint BUILD 新增限定范围 CPU 证明见
  [source-to-BUILD](NATIVE_JOINT_BUILD_SOURCE.md)，已通过 #829 交付；真实 CU 执行未验收，
  完整五阶段65引用也待实现。
- [PR #828](https://github.com/OpenGPGPU/OpenGPU/pull/828) 已合入 `346d96cfd`，准确 head `1dba3adbd`；正常 CI
  `43688426845` 的八个 job 和 ci-ok 全通过，临时交付树/分支已清理。
  该交付将 P16 联合 BUILD 和 GNQ/GVM 元数据捕获合为一次源码交付，原分支
  `codex/p16-joint-delivery`，保留原逻辑提交 `3de037bd8`、`6916cefda`。
  [BUILD 范围与复现](PROTECTED_TESSELLATION_JOINT_BUILD.md)、
  [元数据捕获范围与复现](TESSELLATION_JOINT_METADATA_CAPTURE.md)：准确 BUILD 交付源完整
  host 检查通过（767.27s，768MiB aggregate 守卫，152 pytest、21 strict producer、
  六项环境 skip）；reader 重放后的新鲜10源编译、1项 ABI 和5项实际组件 RTL
  全通过；四个新 suite 已登记 system-core，integration/large-PR 目录检查通过。
  实际 GNT/GVS/GND 串行捕获与执行父级、无 shader clear 路由、完整原程序资源图、
  实际 CU/三域 protected submit/retirement 仍待完成，本批不关闭 P16。
  同时最多开放一个 alignment PR，前一份合入后才开放下一份。
- [PR #829](https://github.com/OpenGPGPU/OpenGPU/pull/829) 已合入，合并交付
  P17 linked TCS 间接输出存储、原四阶段 source-to-BUILD adapter 和独立原资源图检查。
  [最终结果与复现](NATIVE_JOINT_BUILD_SOURCE.md)：53 TU 全新编译、八组原 linked ESSL
  的正常/零工作 invocation/无 invocation 三类实际 BUILD 图、七项源码负控通过；
  完整 host 检查及 ARM64 模块 Kbuild 通过。源码交付仍保留真实 CU、完整五阶段/65引用、
  protected 执行与产品验收边界，不关闭 P16/P17。严格 `ci-ok` 成功后已 rebase auto-merge。
- P07 的十一条recipe/八组原ESSL已在最终source重新绑定的完整112字DUT全部通过：
  inc/read/dec、两组动态索引、scalar末地址及四R32UI image/四SSBO/独立counter共存；
  独立返回值、最终counter、全字节guard、held/retired、PTE/shootdown/context cleanup、
  进程退出与执行后输入复查均通过，十一条消费者清理均确认。
  [最终结果、逐case reset范围与复现](ATOMIC_COUNTER_LIVE_OWNER.md#actual-complete-host-execution-p07)。
  本交付受保护合入后完成数为7/40，下一串行目标为P08的typed image live提交。
- P15、P16、P17、P39、P40 已有下表列出的限定范围源码与检查，均未完成目标验收。
  当前112仍非完整资源资格；旧96模型不能替代P04的完整factory和原probe验收。

这里的 7/40 只统计本次列出的剩余工作，不是整个项目的历史完成率。
现有源码和组件证据不因列入闭合任务而被当作从零开始，也不会据此推算
一个未经验收的总完成百分比。

## 执行和状态规则

1. 每个编号可以通过多个有明确范围、局部验收和剩余边界的阶段 PR 交付。
   一个阶段实现及相关本地检查通过后即提 PR，不等待整个编号或联合 GPU 验收。
   阶段合入不等于工作包完成；**同时只开放 1 个 alignment PR，合入后再开下一个**。
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
| GPU `main` | 已通过受保护 CI 并合入的正式源码；最近基线为 #829；P15/P16/P39部分源码已合入，完整目标验收仍待完成 |
| GPU #828 已合入 | 联合 BUILD 与 GNQ/GVM 元数据捕获；准确 head1dba3adbd/merge346d96cfd，正常CI八job成功，临时交付树/分支已清理；完整P16仍待验收 |
| GPU #829 已合入 | [PR #829](https://github.com/OpenGPGPU/OpenGPU/pull/829) 整合 linked TCS 间接输出存储和同一原四阶段/52引用到 joint BUILD 的源码与独立检查；已通过受保护 CI 并 rebase 合入正式 main |
| GPU `codex/native-sample-dut-bridge` | 保留 P02/P03 的原开发源码，按本清单分批交付；不另外开放并行 PR |
| GPU `codex/owner-counter-live` | 保留P07原开发源码；完整十一条语料已由后续交付源在完整112字DUT验收通过，最终范围见P07结果 |
| GPU `codex/p15-tess-checked-uniform` | P15 原 checked-uniform/版本化 ABI 源码；交付分支保留三项实现逻辑提交，不带旧政策/CI 父链 |
| GPU `codex/p16-protected-tess` | P16 私有原 GEM/RX/validator、联合 BUILD 与默认关闭元数据捕获实现；完整提交/执行仍未闭合 |
| GPU `codex/p17-tess-mesa` | P17 原 Mesa frontend/emitter 开发源码，已提交至d2ec07554；四阶段 linked frontend/52引用 source lease 和 linked TCS 间接输出存储分别经 #827/#829 交付，完整五阶段 GS 编译仍在开发；CPU 通过不等于原 CU 执行 |
| GPU `codex/p39-vertex-patch-source` | P39 原 draw slice、范围 sequencer、GVS1 reader 与 VS→patch ACK/seal 源码；限定组件 RTL 已通过，联合生产接线/CU/native 三域待验收，不启用正式入口 |
| GPU `codex/p40-dynamic-tes-patch` | P40 动态 TES typed read/CU/provider、独立语言输入与 memory adapter/fabric；单 CU 语言 ISA、9bc86886a 原 ordinal binding 及三域完整容量父级 CIRCT 已按各自范围通过；后者 externalImages 不含实际 CU/render，三域原程序联合执行待验收 |
| GPU `codex/alignment-host-integration` | 旧96模型的冻结输入；供现有验证消费者使用，不是新的完整目标版本 |
| LLVM `codex/p40-tes-patch-isa` | 独立ISA候选源码cc37fd175；fn33动态patch read与fn37语言输入MC，重新生成完整RISCV表/16MC源并链接的实际11文件21 RUN通过；不替换主LLVM目录或自动切换正式GPU工具配置 |
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
| P03 | tess sample/image 路由、ordinary112 双 owner oracle/controller、LIVE 反例及独立112 blit 入口源码交付 | 交付后39个补丁；保留 cleanup 退出竞态修复、真实 C 拒绝检查、43包 blit 守卫；与完整开发树逐路径核对并通过 CI | P02 | [#821](https://github.com/OpenGPGPU/OpenGPU/pull/821)；39项原逻辑提交并集、完整host、局部CPU/ASan/UBSan及GCC修复通过；当前head全部67项受保护CI成功，源码交付验收完成并已合入 |

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
| P04 | 新完整目标 factory 的 RTL、模型和执行器资格 | 新鲜生成；全容量、assertions、源码/工具/分片/二进制绑定与原 probe 一致；具备可复现的实际执行入口。后续合同变化须重做此绑定 | P01–P03 | 完成（随本交付受保护合入生效）；[#845](https://github.com/OpenGPGPU/OpenGPU/pull/845)、[#846](https://github.com/OpenGPGPU/OpenGPU/pull/846)、[#847](https://github.com/OpenGPGPU/OpenGPU/pull/847)交付完整sample factory、assertion模型、四个原执行器及三个实际112字probe；[结果与复现](OWNER_FULL_FACTORY_REPRODUCTION.md)。shader/API、资源共存、clock/FPS仍按后续目标验收 |
| P05 | 原 Mesa graphics/sample 的完整 host 双 owner 执行 | 实际 shader、原 GEM/Sv32、同 RAM/无中间 reset；两次结果由独立图像/GNY2/guard oracle 验证；实际 retirement、PTE/shootdown/context cleanup ACK 全部成功 | P04 | 完成（随本交付受保护合入生效）；原Mesa22.3.6 ABI1双owner在完整112字sample host实际通过；[范围与复现](NATIVE_SAMPLE_GRAPHICS_OWNER.md#actual-complete-host-execution-p05)。clock/FPS与后续功能矩阵保持独立 |
| P06 | 43个原调用者 framebuffer blit 的目标 host 执行 | 原43包及 source SHA 不变；精确字节/VM guard、source fault、延迟 R/B reset/drain、健康 ID 复用在目标 DUT 上通过 | P03、P04 | 完成（随本交付受保护合入生效）；完整112字host的43包与source fault、R/B reset/drain、partial-cancel字节及健康ID复用全部实际通过；[最终结果、bounded VM范围与复现](NATIVE_FRAMEBUFFER_BLIT_SAMPLE_OWNER.md#actual-complete-host-execution-p06)。原Linux trace、完整资源与产品clock/FPS仍保留独立门槛 |
| P07 | 原 GEM atomic-counter live 提交与完整操作语料 | 实际 Mesa→生产 ioctl→目标 DUT；inc/read/dec、动态索引及资源共存；独立返回集合/最终 counter/全字节 guard，保留真实 owner 至 cleanup ACK | P04 | 完成（随本交付受保护合入生效）；最终source绑定的完整112字host十一条recipe/八组原ESSL全部实际通过，含scalar末地址和四image/四SSBO共存；独立结果/guard、held/retired、PTE/shootdown/context cleanup及输入复查全部通过；[最终结果、逐case reset范围与复现](ATOMIC_COUNTER_LIVE_OWNER.md#actual-complete-host-execution-p07)。完整资源、跨命令coherence和产品clock/FPS保留独立门槛 |
| P08 | Typed image load/store/atomic live 提交 | 原 image view、format/pitch/layer/权限与精确 writable view；实际 shader 结果、边界、fault/cancel/reset 及独立 oracle 通过 | P04、P07 | image生产ioctl、精确view guard、Gallium typed mip/layer分配和XYZ lowering已实现；208项transport、65组Mesa编译/反射、65组TGSI operand和52组layout/transfer通过；70条原ESSL→生产ioctl CPU捕获、严格GIU1/PTE/权限/精确写范围与7745拒绝检查、counter11条回归通过，独立返回字节oracle及case/source资格接入已实现；[阶段范围与复现](TYPED_IMAGE_LIVE_OWNER.md)。实际typed shader结果、image atomic、边界/fault/cancel/reset与完整DUT仍待验收，未关闭P08 |
| P09 | Indirect compute 原始 source 与执行合同 | 专用 opcode14 capture；GPU产生的三维 grid、依赖 fence/snapshot、零工作、极限/越界、失败与复用均通过，不靠 opcode 白名单 | P04、P07 | 待补齐 |
| P10 | Storage/global/image atomic 完整操作与竞争 | 全规定操作、CAS 精确旧值、竞争与重排、延迟 ACK、reservation、只读/越界、故障后健康复用；实际完整 host 验证 | P07、P08 | 待闭合 |
| P11 | 多 owner、多命令和跨域 coherence/barrier | 同 RAM、无 reset，GPU producer 与 warmed consumer；SSBO/image/texture/attachment/DMA/counter/shared 的 publication、barrier、fence 和 cleanup 矩阵通过 | P05–P10 | 待补齐 |
| P12 | Compute 资源共存、极限与 robustness | 保留128本地线程/16KiB共享及4SSBO、4image、8counter、32texture、12UBO/default；同时使用、末地址、namespace/alias/permission、cancel/reset 均有完整 host 证据 | P07–P11 | 待闭合 |
| P13 | Compute ESSL/ISA/驱动语义闭合 | 原编译器字节和反射绑定；flow、barrier/mask、资源索引及实际 ioctl 路径覆盖 E31-01–05；接口和能力查询与实测一致 | P07–P12 | 待闭合 |

P07 已通过原提交链和资源共存的实际 DUT 验收；各case分别reset，
跨命令、同RAM无reset的H7 coherence仍按P11验收。

## H8 stage、uniform、texture 与 sample 合同

| 编号 | PR 范围 | 验收条件 | 依赖 | 当前状态 / PR |
|---|---|---|---|---|
| P14 | 原 GS→FS ABI7 与 GS/TF 同 host 语料 | 原 ESSL GS、非零16-slot varying、ABI7 FS、sample/mask、4 MRT；TF在clip/flat前的原始输出及render规则、discard/fault/cancel/ACK/无reset复用精确验证 | P03、P04 | 已有组件/保护源码，组合语料待补 |
| P15 | TCS/TES checked-uniform RTL 与版本化 ABI | 明确 stage4/5 root/provider，实际 CU scalar/vector load与writeback、仲裁/drain；block0/11/default31、16KiB/1024 defaults和错误stage/version/map、fault/cancel/reset通过；冻结旧GUR/GNT合同 | P03；新合同重做P04 | [源码PR #822已合入](https://github.com/OpenGPGPU/OpenGPU/pull/822)，合入提交cafc53e09；正常CI全部80项job通过，包含完整编译、host/unit、4-lane/two-warp实际CU isoline client、system input/viewport及ci-ok；原CPU ABI/catalog/parser、27核心源plugin、13组件RTL、layout及5生成器核对通过；三域task-local cancel/fault在实际报告消费后清理，另9源编译、6项定向RTL与2项原代码负控通过；默认关闭，全容量实际CU load/writeback/仲裁/drain、三域及完整资源/故障复用目标验收待完成，源码交付不计目标完成 |
| P16 | Protected TCS/TES program、uniform、GNQ/GNT admission | 原 GEM code/default/UBO进入正确 RX/R-NX私有视图；真实可达性、writer冲突、immutable snapshot、引用、owned patch RAM/level 初始化隔离、三domain执行、retirement/cleanup闭合；只消费实际验证的硬件版本/容量/路由 | P15、P39、P40 | 原 GEM mapper、严格 validator/RX/MMU、2048耦合容量和共享标量分歧修复已有；GVS1/GNT7–9 exact ABI、原VS/GUR1/GND root-only owner/MMU encoder及10项变异负控经Clang/GCC通过；[源码PR #823已合入](https://github.com/OpenGPGPU/OpenGPU/pull/823)，合入提交c784cc1ee，交付临时树/分支已清理；保留原12逻辑提交；Clang/GCC13.3 ASan/UBSan五套正例及53语义负控、实际完整host回归（709.82s，Mesa/缺失target按原脚本跳过）与49项执行器相关CPU检查通过；准确交付源的Linux7.2.0 ARM64 Kbuild实际编译27对象+2helpers、MODPOST及gpu_drv.ko链接通过，未加载；BUILD仍GNT4/5/6静态TES、GVS仍root-only，联合新BUILD/parent和真实protected submit/retirement待闭合，未注册usable ioctl/probe；后续原draw/invocation完整捕获、program v3及独立FS8/9私有profile源码已通过[#825](https://github.com/OpenGPGPU/OpenGPU/pull/825)合入，[结果与限制](P16_INVOCATION_SOURCE_DELIVERY.md)；新GNQ4/5/6联合BUILD与GNQ/GVM默认关闭reader已实现并通过准确交付源完整host及6项ABI/组件检查，见[BUILD](PROTECTED_TESSELLATION_JOINT_BUILD.md)与[metadata](TESSELLATION_JOINT_METADATA_CAPTURE.md)；实际GNT/GVS/GND串行capture/parent/clear/CU/submit仍待闭合，不关闭P16 |
| P17 | 原 Mesa TCS/TES lowering 与 API72 无损映射 | binding0..71→每stage至多12 blocks→独立私有表；真实ESSL/反射/GEM/GUR/GUB闭合，binding71、rebind、同GEM不同view、跨stage复用及60个graphics引用正确 | P15；与P16/P39/P40联合执行 | 六个逻辑源码提交已通过[源码PR #826](https://github.com/OpenGPGPU/OpenGPU/pull/826)合入（基于#825，完整host/Mesa/P16字节检查通过）；已合入开发源码至53103920c；原 Mesa 三组语言input-patch ID ESSL产生6个TCS/TES程序，独立P16 v3 validator/RX/MMU通过；旧3组v1和5组v2程序与独立原emitter逐字节一致，API72/五stage60-block反射、原GEM资源生命周期和五项语言负控通过；ordinary FS8/9两frontend产生29个原程序并经独立P16实际字节消费；ordinary FS8/9 的 opaque program、原26资源source lease及metadata-derived draw CPU生命周期已实现，五组原Mesa程序和独立P16字节消费通过，见[NATIVE_GRAPHICS_PRIMITIVE_LIFECYCLE.md](NATIVE_GRAPHICS_PRIMITIVE_LIFECYCLE.md)；同一原链接四阶段52引用与两独立map的CPU编译/lease检查已通过[#827](https://github.com/OpenGPGPU/OpenGPU/pull/827)合入，三组原Mesa/45 fresh TU/12原程序独立P16字节消费通过，见[NATIVE_JOINT_COMPILER_SOURCE.md](NATIVE_JOINT_COMPILER_SOURCE.md)；原indirect TCS patch store、52资源完整GUR/joint BUILD、五阶段65引用、protected submit、生产语言binding与实际CU/API待闭合 |
| P18 | Tessellation domain/spacing/primitive generation 完整语义 | isoline/triangle/quad、所需spacing/winding/边界、不变性、amplification/overflow、primitive bounds与实际protected stages组合；独立几何/图像和fault/order oracle | P15–P17、P39、P40 | 待闭合 |
| P19 | 96 combined texture namespace 与六stage checked sampler | 各stage16 units及全局accounting、stage-local无损映射、TCS/TES真实采样返回；原resource来源、immutable view、writer/fault/drain/cleanup完整 | P15–P17 | 待实现 |
| P20 | Sample-rate、显式插值与 shader sample I/O | 实际1x/2x/4x、center/sample/centroid/offset、sampleID/position/mask、alpha-to-coverage/discard/depth/MRT/resolve；原shader输入输出及完整host结果独立检查 | P05、P14、P18 | 待闭合 |
| P21 | Advanced blend、indexed MRT 与对应原API状态 | 所需方程、独立enable/factor/equation/mask、sRGB、barrier、float附件与clear/visibility组合；原状态提交到native tile结果及guard一致 | P05、P20 | 待闭合 |
| P22 | GLES3.2 texture builtins 与特殊 target 的整合 | 动态resource array、gather/offset、buffer texture、border、cube-array、stencil、multisample-array；原编译/driver/ISA/各stage实际取样和limits均验证 | P19 | 待闭合 |
| P23 | ASTC及压缩/颜色格式完整原路径 | 全规定ASTC LDR footprints/modes、ETC2/EAC/sRGB等目录与decoder/采样绑定；独立格式oracle、非法块/边界/fault及目标host组合证据 | P19、P22 | 待闭合 |
| P39 | 原 VS 输入/实例/index/restart→owned patch 的生产链 | 保留原 draw、实际原 ESSL/Mesa VS 执行与 GPU patch assembly；原 attribute/index/instance/divisor、linked component、同 patch identity/来源正确；输入逐字 ACK 后再 seal；原 code/resource authority、zero-init 隔离、fault/cancel/drain/retirement、三域实际组合验证，不用 CPU 预制 patch 关闭 | P15、P17；与P16联合验证，完整模型重做P04 | 原draw slice、VS wrapper、ACK/seal publisher、zero-init和GPU范围sequencer在#824中从#823重放15个逻辑源码提交并已合入；准确交付源9+2+5新鲜编译、10项组件RTL和152项selector检查通过，[结果/限制](P39_SOURCE_DELIVERY.md)，[源码PR #824](https://github.com/OpenGPGPU/OpenGPU/pull/824)已通过正常CI/ci-ok并合入，临时交付分支已清理；GVS1/original GND1严格reader、两者实际串行capture、GNT7–9 reader、VS路由/owner和三域typed image parent已在P40开发树实现；限定组件RTL及独立负控通过，其中capture为3绿/2精确断言红；79当前源plugin编译通过；初次128MiB elaboration OOM保留，5969a1f14的完整容量isoline parent在768MiB heap下实际CIRCT lowering通过（2CU接口、16×8、512KiB payload/64KiB单项、65536 paged、externalImages=true），发现并修复Owner/Publisher取消组合环，四项实际组件RTL和恢复原环的精确负控通过；9bc86886a已接入原始ordinal除以inputVertices的独立语言ID：三域typed range和TCS/TES launch绑定有9项实际组件/兼容RTL及2项精确原代码负控，三域全容量父级CIRCT分别19.09/19.59/19.56s通过（768MiB heap/1536MiB aggregate守卫），externalImages仍无实际CU/render实例；完整ABI8/9执行生命周期、生产BUILD和原compiler→实际CU/native三域链仍未闭合，不用CPU预制patch替代 |
| P40 | TES 完整动态 patch read service、ISA 与原编译器 lowering | 在支持的32×64/120/2048容量内动态读取任意合法 control vertex/component、per-patch 与 tess levels；TES coords/原实例语义、实际 CU返回/writeback、越界/stage/version、仲裁及fault/cancel/drain；原 Mesa 动态索引程序与三域 protected 组合验证，ISA变化同步独立LLVM仓库 | P15、P17；与P16联合验证，完整模型重做P04 | 默认关闭的typed read/CU/provider、memory adapter及staging独立drain已有源码；后者12个case/14次限定组件仿真含RO/RW Sv32、render未退役时VS局部handoff与global flush等待、获grant前取消欠账；原Mesa5动态+3旧v1 CPU及P16原字节检查、独立LLVM候选cc37fd175的完整RISCV表生成、16MC源编译及11文件21 RUN实际MC检查通过（fn33/fn37、原object往返/featureoff/invalid/reserved），重用target-independent通用库，未构建完整LLVM；独立语言binding的实际单CU测试在f1bc7d2ef通过（16lane/8warp物理容量，TCS实际2warp/TES1warp，直接硬件binding和literal ISA，84.09s），未执行原Mesa程序或protected BUILD；5969a1f14完整容量isoline parent实际CIRCT lowering通过20.04s，768MiB heap/1536MiB aggregate guard，externalImages无实际CU/render实例；9bc86886a的独立原ordinal语言ID已贯穿三域typed range→ImagePipeline→TCS/TES launch；三域全容量父级实际CIRCT通过（19.09/19.59/19.56s），externalImages仍无实际CU/render；完整TCS/GS lowering与LLVM生产工具接入、三域原程序实际CU与joint BUILD/protected执行仍待验收 |

P39/P40 三域 source0 load-reply 路由修复 `3cc869a9a` 已在开发 topic 提交，尚未正式交付；四项组件正向和恢复原始三域源码的精确红向通过，完整2CU新模型重建待验。该修复和元数据检查不关闭工作包。

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
