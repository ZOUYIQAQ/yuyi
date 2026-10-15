# 模型下载源核查

核查日期：2026-10-15（北京时间）。本文件记录指定模型的来源、逐文件完整性、许可证据与网络限制。仅新增本文件及 `.qa/model-source-check/` 内核查材料；不涉及代码、安装目录或 Git 提交。

## 可直接接入的结论

- SenseVoice-Small **2024-07-17 int8**：ModelScope 两文件均实下载，与固定 HF revision 及 HF-mirror 内容一致。源文件 `model.int8.onnx`、`tokens.txt` 对应本地 `asr/model.int8.onnx`、`asr/tokens.txt`。
- ReazonSpeech **日英 ja-en epoch-35**：四文件均已由固定 HF revision 与 HF-mirror 完整下载确认，匹配核查开始时 `setup.sh` 固定值。ModelScope 可提供相同 encoder、joiner、tokens；**未找到相同的浮点 decoder**，该文件使用 HF-mirror 后备。不能声称已具备完整 ModelScope 四文件组合。
- SileroVAD：ModelScope、HF-mirror 与 sherpa-onnx 官方发布文件均完整下载一致；另 ModelScope 完整文件已通过物理接口直连下载验证。
- 下载顺序按每文件列出的顺序执行，通常是 ModelScope → HF-mirror → 官方；只有 Reazon decoder 从 HF-mirror 开始。镜像站只是候选传输源，不代表官方身份或中国境内托管保证。
- 两套 ASR 是独立模型包，均使用 `asr/tokens.txt`，需分别存储/选择，不能将两份词表混装。源优先级只切换同一 SHA256 的文件，不切换模型版本。
- 机器可读交接：[handoff-models.json](../.qa/model-source-check/handoff-models.json)，字段为 `model/path/sizeBytes/sha256/sources/evidence`。完整传输终点另见 [verified-sources.json](../.qa/model-source-check/verified-sources.json)。

## 逐文件固定清单

大小单位均为精确字节，SHA256 均由完整文件实算。以下 HTTP 下载成功同时满足 curl 返回成功和本地字节数/哈希一致；ModelScope 元数据也已比对。

### ReazonSpeech 日英 int8

| 本地相对路径 | 源文件名 | sizeBytes | SHA256 |
| --- | --- | ---: | --- |
| `asr/decoder.onnx` | `decoder-epoch-35-avg-1.onnx` | 5165084 | `90b60985672df5023e187e9d25aa2dc93abc4286bfacf71a35a78a6b76d88df5` |
| `asr/encoder.int8.onnx` | `encoder-epoch-35-avg-1.int8.onnx` | 70876409 | `ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268` |
| `asr/joiner.int8.onnx` | `joiner-epoch-35-avg-1.int8.onnx` | 1033417 | `c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495` |
| `asr/tokens.txt` | `tokens.txt` | 26631 | `144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24` |

**`asr/decoder.onnx` 下载入口（按优先级）**

1. <https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/decoder-epoch-35-avg-1.onnx>
2. <https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/decoder-epoch-35-avg-1.onnx>

**`asr/encoder.int8.onnx` 下载入口（按优先级）**

1. <https://modelscope.cn/models/yinannn/yinanyi-reazon-ja/resolve/ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f/encoder-epoch-35-avg-1.int8.onnx>
2. <https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/encoder-epoch-35-avg-1.int8.onnx>
3. <https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/encoder-epoch-35-avg-1.int8.onnx>

**`asr/joiner.int8.onnx` 下载入口（按优先级）**

1. <https://modelscope.cn/models/yinannn/yinanyi-reazon-ja/resolve/ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f/joiner-epoch-35-avg-1.int8.onnx>
2. <https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/joiner-epoch-35-avg-1.int8.onnx>
3. <https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/joiner-epoch-35-avg-1.int8.onnx>

**`asr/tokens.txt` 下载入口（按优先级）**

1. <https://modelscope.cn/models/yinannn/yinanyi-reazon-ja/resolve/ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f/tokens.txt>
2. <https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/tokens.txt>
3. <https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/tokens.txt>

### SenseVoice-Small 2024-07-17 int8

| 本地相对路径 | 源文件名 | sizeBytes | SHA256 |
| --- | --- | ---: | --- |
| `asr/model.int8.onnx` | `model.int8.onnx` | 239233841 | `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` |
| `asr/tokens.txt` | `tokens.txt` | 315894 | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` |

**`asr/model.int8.onnx` 下载入口（按优先级）**

1. <https://modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/73eca47697f980daa3d16112404174b6b950b514/model.int8.onnx>
2. <https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/model.int8.onnx>
3. <https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/model.int8.onnx>

**`asr/tokens.txt` 下载入口（按优先级）**

1. <https://modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/73eca47697f980daa3d16112404174b6b950b514/tokens.txt>
2. <https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/tokens.txt>
3. <https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/tokens.txt>

### SileroVAD

| 本地相对路径 | 源文件名 | sizeBytes | SHA256 |
| --- | --- | ---: | --- |
| `vad/silero_vad.onnx` | `silero_vad.onnx` | 643854 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |

**`vad/silero_vad.onnx` 下载入口（按优先级）**

1. <https://modelscope.cn/models/xnnehang/k2-fsa-silero-vad/resolve/e64bab140550f46d724405a77ce842d2095feeef/silero_vad.onnx>
2. <https://hf-mirror.com/mobilebytesensei/betterflow-silero-vad/resolve/0ae41137f2cd00de5e8a286fe01bdd5e3644f2da/silero_vad.onnx>
3. <https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx>

## 固定版本与排除项

Reazon 官方 ONNX 发布者仓库固定为 `csukuangfj/reazonspeech-k2-v2-ja-en@12b44671ff48b9aed622551d64e02fea9a2cf870`；其模型卡指向 Reazon 原始上游。原始上游卡片请求返回 401，不能将其作为已验证可下载源。SenseVoice 固定为 `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17@2365baeacb507f821a0c8120fcee3d484dba7a07`，这里只使用 int8 模型和词表。

- ModelScope `yinannn/yinanyi-reazon-ja@ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f` 的名称虽含 ja，但 encoder/joiner/tokens 的实际内容经完整哈希证明与目标日英版本一致。它的 decoder 是 `decoder-epoch-35-avg-1.int8.onnx`，大小 1308690、SHA256 `d0179db78a2e65445c5c3dc41e94c62068fc539fe4e45060e32f438cca76432f`，**不等于**目标 `decoder-epoch-35-avg-1.onnx`，不可替换。
- ModelScope `admin2558/lingualive-reazonspeech-ja-int8` 与 `lijiu09/reazonspeech-k2-v2` 是 epoch-99 组合，encoder 大小 154670139、SHA256 `2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d`，词表大小 45754、SHA256 `2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def`；均不属于当前日英固定组合，未下载。
- ModelScope `pengzhendong/silero-vad` 根目录当前大小 2327524、SHA256 `2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f`，与当前 643854 字节文件不同，不能因同名替换。v4/v5 和其他 int8 导出也不混用。
- 常见猜测路径 `modelscope.cn/.../csukuangfj/...` 曾返回 404，已保留证据，不能把尚未存在的地址作为备用源。HF `csukuangfj/silero-vad` API 返回 401；本次改用经过字节一致性验证的社区转存作 HF-mirror 入口。
- 旧索引中的 SenseVoice GitHub 压缩包未留下完成结果；本次不将其列为已验证源，也不为重复核查额外下载压缩包。未下载 Qwen 或其他大模型。

## 许可与证据边界

| 组件 | 本次可确认的许可 | 证据与限制 |
| --- | --- | --- |
| ReazonSpeech 日英 ONNX 文件组 | 上游项目 Apache-2.0；指定 ONNX 权重未发现独立 LICENSE 文件 | [ReazonSpeech 上游 LICENSE](https://github.com/reazon-research/ReazonSpeech/blob/master/LICENSE) 与 [上游 README](https://github.com/reazon-research/ReazonSpeech) 提供 Apache-2.0 项目声明；[ONNX 发布者模型卡](https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en) 指明原始来源，但缺少权重专门声明，原始权重仓库卡片当前 401。故记录为上游许可证据，不冒充已取得额外的权重授权确认。 |
| SenseVoice-Small int8 与 tokens | FunASR Model Open Source License Agreement，版本 1.1，自定义模型协议 | [固定导出仓库 LICENSE](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/blob/2365baeacb507f821a0c8120fcee3d484dba7a07/LICENSE) 指向 FunASR；[官方模型卡](https://huggingface.co/FunAudioLLM/SenseVoiceSmall/blob/main/README.md) 标注 `other` 并链接 [MODEL_LICENSE](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)。FunASR 工具代码的 MIT 不能套给模型权重。 |
| SileroVAD | MIT（原始上游） | [Silero 固定上游 LICENSE](https://github.com/snakers4/silero-vad/blob/1e261b036686cd0017d500ee96acd1c4ba572a9d/LICENSE)；[sherpa-onnx 文档](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) 提供分发背景。本次下载 ModelScope 卡片模板写 Apache-2.0，与原始上游 MIT 不一致，不能用镜像卡片覆盖上游许可。 |

SenseVoice 模型协议允许在其条款下使用、复制、修改和分享，要求注明出处、作者并保留相关模型名称；另有行为限制、自动终止和协议修订条款。它不是标准 MIT/Apache-2.0。分发时随包保留实际许可文本及来源。该协议文本完整副本为 `.qa/model-source-check/funasr-license-current.txt`，Reazon 和 Silero 的文本分别保存在 `official-reazon-license.txt`、`official-silero-license.txt`。

## 网络验证方法与适用范围

1. 复用原 `download-jobs*.json` 与 `*.result.json`；先重算已完成的本地文件，随后只续传 ModelScope 未完成 encoder 和 SenseVoice int8，并少量补下 Silero。旧的部分文件保留，不作完整文件使用。
2. 常规完整下载使用 `curl --noproxy "*"`、12 秒连接超时、240 秒总超时、600000000 字节单文件上限，续传总超时 420 秒且上限 300000000 字节。Python 只使用 `/Users/qece/Desktop/code/ds_auto_bot/.venv/bin/python`。核查脚本不导入业务程序、不触发飞书告警。
3. `--noproxy` 只禁用显式代理。原成功记录常见远端 `198.18.1.202`、`198.18.1.204` 和本地 `198.18.0.1`，属于本机透明代理路径，不能称作真正直连。仅绑定 `en1` 而沿用假 IP 的旧探测连接超时（curl 28），不证明远端服务不可用。
4. 补测使用 `--noproxy "*" --interface en1 --resolve 主机:443:真实IP`：ModelScope 主站 `39.99.133.195`，HF-mirror `160.16.86.14`，本地物理 IP `192.168.110.213`。两站词表均完整下载、重新计算 SHA256 一致。
5. Silero 完整文件另绑定 `en1`，为主站设置 `39.99.133.195`、为 `cdn-lfs-cn-1.modelscope.cn` 设置 `111.123.55.6`（阿里 DNS HTTPS 查询结果之一），直连完整下载 643854 字节，SHA256 仍为 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`。原始传输记录为 `direct-vad-transfer.json`、响应头为 `direct-vad.headers`。
6. 以上只证明本机、本次时间及对应网络出口。首轮未覆盖全部ASR大文件；最终补测已完整覆盖两套模型所有文件，结果见文末。不声称全国网络可用，也不保证镜像站/CDN长期稳定或未来 revision 可访问。HF-mirror 某些常规请求最后跳到 HF/CDN，不能把国内入口等同于全程境内传输。
7. 下载器须检查完整大小和完整 SHA256，失败时拒绝激活或保存为可用状态；不得把 HTML、API 错误 JSON、LFS 指针或部分下载当模型。HTTP 200、Content-Length、ETag、Xet 内容键均不能单独代替本表的 SHA256。

## 每文件实际最终 URL（本次传输快照）

下面同时列出稳定入口和 curl `url_effective` 最终地址。CDN 地址中签名参数原样保留，仅供审计，具有时效性；程序必须保存上面的固定 revision 入口并重新跟随重定向，不应持久化过期签名 URL。逐条证据 JSON 在临时目录内。

### reazon / asr/decoder.onnx / mirror__reazon__decoder-epoch-35-avg-1.onnx.result.json

- 稳定入口：<https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/decoder-epoch-35-avg-1.onnx>
- 最终 URL：<https://us.aws.cdn.hf.co/xet-bridge-us/6789c2cf2b4189d8cec017dc/239e6b202eaa14444dfa5e3f8d19ca41534efd81cff40856aa9b2acd0ae5ab74?xip=eKqk3OAD6Bs&X-Xet-Cas-Uid=public&user_id=public&response-content-disposition=inline%3B+filename*%3DUTF-8%27%27decoder-epoch-35-avg-1.onnx%3B+filename%3D%22decoder-epoch-35-avg-1.onnx%22%3B&Expires=1792039920&Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly91cy5hd3MuY2RuLmhmLmNvL3hldC1icmlkZ2UtdXMvNjc4OWMyY2YyYjQxODlkOGNlYzAxN2RjLzIzOWU2YjIwMmVhYTE0NDQ0ZGZhNWUzZjhkMTljYTQxNTM0ZWZkODFjZmY0MDg1NmFhOWIyYWNkMGFlNWFiNzRcXD94aXA9ZUtxazNPQUQ2QnMmWC1YZXQtQ2FzLVVpZD1wdWJsaWMmdXNlcl9pZD1wdWJsaWMmcmVzcG9uc2UtY29udGVudC1kaXNwb3NpdGlvbj1pbmxpbmUlM0IrZmlsZW5hbWUlMkElM0RVVEYtOCUyNyUyN2RlY29kZXItZXBvY2gtMzUtYXZnLTEub25ueCUzQitmaWxlbmFtZSUzRCUyMmRlY29kZXItZXBvY2gtMzUtYXZnLTEub25ueCUyMiUzQiIsIkNvbmRpdGlvbiI6eyJEYXRlTGVzc1RoYW4iOnsiRXBvY2hUaW1lIjoxNzkyMDM5OTIwfX19XX0_&Signature=MEQCIEVrzXAsGgWcDux5tfSGoPPGA4fBn7WgCaGXqrEtKngoAiBL4jaZslOPNnXk-cTNN426x%7E4C60AX86UQlVJVCa6agA__&Key-Pair-Id=01KXEF4KZ1B6FV465MAWR4M21F&Hash-Algorithm=SHA256>
- HTTP：200；最后远端 IP：`198.18.1.204`；完整大小：5165084；SHA256：`90b60985672df5023e187e9d25aa2dc93abc4286bfacf71a35a78a6b76d88df5`。

### reazon / asr/decoder.onnx / hf__reazon__decoder-epoch-35-avg-1.onnx.result.json

- 稳定入口：<https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/decoder-epoch-35-avg-1.onnx>
- 最终 URL：<https://us.aws.cdn.hf.co/xet-bridge-us/6789c2cf2b4189d8cec017dc/239e6b202eaa14444dfa5e3f8d19ca41534efd81cff40856aa9b2acd0ae5ab74?response-content-disposition=inline%3B+filename*%3DUTF-8%27%27decoder-epoch-35-avg-1.onnx%3B+filename%3D%22decoder-epoch-35-avg-1.onnx%22%3B&user_id=public&X-Xet-Cas-Uid=public&xip=eKqk3OAD6Bs&Expires=1792039917&Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly91cy5hd3MuY2RuLmhmLmNvL3hldC1icmlkZ2UtdXMvNjc4OWMyY2YyYjQxODlkOGNlYzAxN2RjLzIzOWU2YjIwMmVhYTE0NDQ0ZGZhNWUzZjhkMTljYTQxNTM0ZWZkODFjZmY0MDg1NmFhOWIyYWNkMGFlNWFiNzRcXD9yZXNwb25zZS1jb250ZW50LWRpc3Bvc2l0aW9uPWlubGluZSUzQitmaWxlbmFtZSUyQSUzRFVURi04JTI3JTI3ZGVjb2Rlci1lcG9jaC0zNS1hdmctMS5vbm54JTNCK2ZpbGVuYW1lJTNEJTIyZGVjb2Rlci1lcG9jaC0zNS1hdmctMS5vbm54JTIyJTNCJnVzZXJfaWQ9cHVibGljJlgtWGV0LUNhcy1VaWQ9cHVibGljJnhpcD1lS3FrM09BRDZCcyIsIkNvbmRpdGlvbiI6eyJEYXRlTGVzc1RoYW4iOnsiRXBvY2hUaW1lIjoxNzkyMDM5OTE3fX19XX0_&Signature=MEUCIBlDfxeViPJXfn-Z8CWK9P-rAnid%7EKbvHYspxbzxQSBOAiEAzk1J%7EPZXAWZebtjLUNYmNHYTMfD%7E9OgZRA7PDCI%7EAUk_&Key-Pair-Id=01KXEF4KZ1B6FV465MAWR4M21F&Hash-Algorithm=SHA256>
- HTTP：200；最后远端 IP：`198.18.1.204`；完整大小：5165084；SHA256：`90b60985672df5023e187e9d25aa2dc93abc4286bfacf71a35a78a6b76d88df5`。

### reazon / asr/encoder.int8.onnx / continued__ms__reazon__encoder-epoch-35-avg-1.int8.onnx.result.json

- 稳定入口：<https://modelscope.cn/models/yinannn/yinanyi-reazon-ja/resolve/ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f/encoder-epoch-35-avg-1.int8.onnx>
- 最终 URL：<https://cdn-lfs-cn-1.modelscope.cn/prod/lfs-objects/ea/d1/579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268?filename=encoder-epoch-35-avg-1.int8.onnx&namespace=yinannn&repository=yinanyi-reazon-ja&revision=ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f&tag=model&auth_key=1792036549-a4dde1282aa9471dac0680f358725285-0-c058864f7c2580ddcff86f410f5fe70a>
- HTTP：206；最后远端 IP：`198.18.1.214`；完整大小：70876409；SHA256：`ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268`。

### reazon / asr/encoder.int8.onnx / mirror__reazon__encoder-epoch-35-avg-1.int8.onnx.result.json

- 稳定入口：<https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/encoder-epoch-35-avg-1.int8.onnx>
- 最终 URL：<https://us.aws.cdn.hf.co/xet-bridge-us/6789c2cf2b4189d8cec017dc/a78e4a058dc57f72b5cc34ace1deadcf0f77caeb5675e07562eb36e09d7a4bc3?response-content-disposition=inline%3B+filename*%3DUTF-8%27%27encoder-epoch-35-avg-1.int8.onnx%3B+filename%3D%22encoder-epoch-35-avg-1.int8.onnx%22%3B&xip=eKqk3OAD6Bs&user_id=public&X-Xet-Cas-Uid=public&Expires=1792039920&Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly91cy5hd3MuY2RuLmhmLmNvL3hldC1icmlkZ2UtdXMvNjc4OWMyY2YyYjQxODlkOGNlYzAxN2RjL2E3OGU0YTA1OGRjNTdmNzJiNWNjMzRhY2UxZGVhZGNmMGY3N2NhZWI1Njc1ZTA3NTYyZWIzNmUwOWQ3YTRiYzNcXD9yZXNwb25zZS1jb250ZW50LWRpc3Bvc2l0aW9uPWlubGluZSUzQitmaWxlbmFtZSUyQSUzRFVURi04JTI3JTI3ZW5jb2Rlci1lcG9jaC0zNS1hdmctMS5pbnQ4Lm9ubnglM0IrZmlsZW5hbWUlM0QlMjJlbmNvZGVyLWVwb2NoLTM1LWF2Zy0xLmludDgub25ueCUyMiUzQiZ4aXA9ZUtxazNPQUQ2QnMmdXNlcl9pZD1wdWJsaWMmWC1YZXQtQ2FzLVVpZD1wdWJsaWMiLCJDb25kaXRpb24iOnsiRGF0ZUxlc3NUaGFuIjp7IkVwb2NoVGltZSI6MTc5MjAzOTkyMH19fV19&Signature=MEUCIQDr2nn0PBolyzFWmfFcd%7E0Cgii1ZwnPOqS4HW%7E64UoIRgIgakmXEx8lSG1QzDRWRTmzCBoatklCj-GRpN57vRhHm0I_&Key-Pair-Id=01KXEF4KZ1B6FV465MAWR4M21F&Hash-Algorithm=SHA256>
- HTTP：200；最后远端 IP：`198.18.1.204`；完整大小：70876409；SHA256：`ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268`。

### reazon / asr/encoder.int8.onnx / hf__reazon__encoder-epoch-35-avg-1.int8.onnx.result.json

- 稳定入口：<https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/encoder-epoch-35-avg-1.int8.onnx>
- 最终 URL：<https://us.aws.cdn.hf.co/xet-bridge-us/6789c2cf2b4189d8cec017dc/a78e4a058dc57f72b5cc34ace1deadcf0f77caeb5675e07562eb36e09d7a4bc3?xip=eKqk3OAD6Bs&user_id=public&X-Xet-Cas-Uid=public&response-content-disposition=inline%3B+filename*%3DUTF-8%27%27encoder-epoch-35-avg-1.int8.onnx%3B+filename%3D%22encoder-epoch-35-avg-1.int8.onnx%22%3B&Expires=1792039917&Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly91cy5hd3MuY2RuLmhmLmNvL3hldC1icmlkZ2UtdXMvNjc4OWMyY2YyYjQxODlkOGNlYzAxN2RjL2E3OGU0YTA1OGRjNTdmNzJiNWNjMzRhY2UxZGVhZGNmMGY3N2NhZWI1Njc1ZTA3NTYyZWIzNmUwOWQ3YTRiYzNcXD94aXA9ZUtxazNPQUQ2QnMmdXNlcl9pZD1wdWJsaWMmWC1YZXQtQ2FzLVVpZD1wdWJsaWMmcmVzcG9uc2UtY29udGVudC1kaXNwb3NpdGlvbj1pbmxpbmUlM0IrZmlsZW5hbWUlMkElM0RVVEYtOCUyNyUyN2VuY29kZXItZXBvY2gtMzUtYXZnLTEuaW50OC5vbm54JTNCK2ZpbGVuYW1lJTNEJTIyZW5jb2Rlci1lcG9jaC0zNS1hdmctMS5pbnQ4Lm9ubnglMjIlM0IiLCJDb25kaXRpb24iOnsiRGF0ZUxlc3NUaGFuIjp7IkVwb2NoVGltZSI6MTc5MjAzOTkxN319fV19&Signature=MEYCIQCfcHW7ZziUn8bY8k79hkWhDvpwzkvLO8Duk6fLyov6bQIhAPdSLbrPuhQfHXK34eYoEhN8WytDhUZESs1zv9Jz69Nr&Key-Pair-Id=01KXEF4KZ1B6FV465MAWR4M21F&Hash-Algorithm=SHA256>
- HTTP：200；最后远端 IP：`198.18.1.204`；完整大小：70876409；SHA256：`ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268`。

### reazon / asr/joiner.int8.onnx / ms__reazon__joiner-epoch-35-avg-1.int8.onnx.result.json

- 稳定入口：<https://modelscope.cn/models/yinannn/yinanyi-reazon-ja/resolve/ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f/joiner-epoch-35-avg-1.int8.onnx>
- 最终 URL：<https://cdn-lfs-cn-1.modelscope.cn/prod/lfs-objects/c7/f4/ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495?filename=joiner-epoch-35-avg-1.int8.onnx&namespace=yinannn&repository=yinanyi-reazon-ja&revision=ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f&tag=model&auth_key=1792036400-666556a6f07443a19bd5c695b8e6e340-0-43093eb59791eb1557f79c8ebb07500f>
- HTTP：200；最后远端 IP：`198.18.1.214`；完整大小：1033417；SHA256：`c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495`。

### reazon / asr/joiner.int8.onnx / mirror__reazon__joiner-epoch-35-avg-1.int8.onnx.result.json

- 稳定入口：<https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/joiner-epoch-35-avg-1.int8.onnx>
- 最终 URL：<https://us.aws.cdn.hf.co/xet-bridge-us/6789c2cf2b4189d8cec017dc/5ec73e1386f93b5febb1226f2242c972b8778ed126f4697c345f2b652020d885?X-Xet-Cas-Uid=public&response-content-disposition=inline%3B+filename*%3DUTF-8%27%27joiner-epoch-35-avg-1.int8.onnx%3B+filename%3D%22joiner-epoch-35-avg-1.int8.onnx%22%3B&xip=eKqk3OAD6Bs&user_id=public&Expires=1792039923&Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly91cy5hd3MuY2RuLmhmLmNvL3hldC1icmlkZ2UtdXMvNjc4OWMyY2YyYjQxODlkOGNlYzAxN2RjLzVlYzczZTEzODZmOTNiNWZlYmIxMjI2ZjIyNDJjOTcyYjg3NzhlZDEyNmY0Njk3YzM0NWYyYjY1MjAyMGQ4ODVcXD9YLVhldC1DYXMtVWlkPXB1YmxpYyZyZXNwb25zZS1jb250ZW50LWRpc3Bvc2l0aW9uPWlubGluZSUzQitmaWxlbmFtZSUyQSUzRFVURi04JTI3JTI3am9pbmVyLWVwb2NoLTM1LWF2Zy0xLmludDgub25ueCUzQitmaWxlbmFtZSUzRCUyMmpvaW5lci1lcG9jaC0zNS1hdmctMS5pbnQ4Lm9ubnglMjIlM0ImeGlwPWVLcWszT0FENkJzJnVzZXJfaWQ9cHVibGljIiwiQ29uZGl0aW9uIjp7IkRhdGVMZXNzVGhhbiI6eyJFcG9jaFRpbWUiOjE3OTIwMzk5MjN9fX1dfQ__&Signature=MEUCICmdNeARnT1os59lywmQriUasr%7E5r5ZhI3XZFzCRAqGvAiEAn5qW0WlyCl1r0szuo-4yEPDE72Ne3gkJeDRaMw9QHHU_&Key-Pair-Id=01KXEF4KZ1B6FV465MAWR4M21F&Hash-Algorithm=SHA256>
- HTTP：200；最后远端 IP：`198.18.1.204`；完整大小：1033417；SHA256：`c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495`。

### reazon / asr/joiner.int8.onnx / hf__reazon__joiner-epoch-35-avg-1.int8.onnx.result.json

- 稳定入口：<https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/joiner-epoch-35-avg-1.int8.onnx>
- 最终 URL：<https://us.aws.cdn.hf.co/xet-bridge-us/6789c2cf2b4189d8cec017dc/5ec73e1386f93b5febb1226f2242c972b8778ed126f4697c345f2b652020d885?response-content-disposition=inline%3B+filename*%3DUTF-8%27%27joiner-epoch-35-avg-1.int8.onnx%3B+filename%3D%22joiner-epoch-35-avg-1.int8.onnx%22%3B&user_id=public&xip=eKqk3OAD6Bs&X-Xet-Cas-Uid=public&Expires=1792039917&Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly91cy5hd3MuY2RuLmhmLmNvL3hldC1icmlkZ2UtdXMvNjc4OWMyY2YyYjQxODlkOGNlYzAxN2RjLzVlYzczZTEzODZmOTNiNWZlYmIxMjI2ZjIyNDJjOTcyYjg3NzhlZDEyNmY0Njk3YzM0NWYyYjY1MjAyMGQ4ODVcXD9yZXNwb25zZS1jb250ZW50LWRpc3Bvc2l0aW9uPWlubGluZSUzQitmaWxlbmFtZSUyQSUzRFVURi04JTI3JTI3am9pbmVyLWVwb2NoLTM1LWF2Zy0xLmludDgub25ueCUzQitmaWxlbmFtZSUzRCUyMmpvaW5lci1lcG9jaC0zNS1hdmctMS5pbnQ4Lm9ubnglMjIlM0ImdXNlcl9pZD1wdWJsaWMmeGlwPWVLcWszT0FENkJzJlgtWGV0LUNhcy1VaWQ9cHVibGljIiwiQ29uZGl0aW9uIjp7IkRhdGVMZXNzVGhhbiI6eyJFcG9jaFRpbWUiOjE3OTIwMzk5MTd9fX1dfQ__&Signature=MEUCIHtG8FsZ6Q2P3sP87f6EbnGnU95dBf1kQ%7Eqhg9gECjfQAiEAoxXeZGlH8kuXOmlUa3X9fAv8cXvfvws26ygBabIuDMQ_&Key-Pair-Id=01KXEF4KZ1B6FV465MAWR4M21F&Hash-Algorithm=SHA256>
- HTTP：200；最后远端 IP：`198.18.1.204`；完整大小：1033417；SHA256：`c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495`。

### reazon / asr/tokens.txt / ms__reazon__tokens.txt.result.json

- 稳定入口：<https://modelscope.cn/models/yinannn/yinanyi-reazon-ja/resolve/ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f/tokens.txt>
- 最终 URL：<https://modelscope.cn/models/yinannn/yinanyi-reazon-ja/resolve/ddfd04fb9a112a3fcfde1e05fc976a9bec07db7f/tokens.txt>
- HTTP：200；最后远端 IP：`198.18.1.202`；完整大小：26631；SHA256：`144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24`。

### reazon / asr/tokens.txt / mirror__reazon__tokens.txt.result.json

- 稳定入口：<https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/tokens.txt>
- 最终 URL：<https://huggingface.co/api/resolve-cache/models/csukuangfj/reazonspeech-k2-v2-ja-en/12b44671ff48b9aed622551d64e02fea9a2cf870/tokens.txt?%2Fcsukuangfj%2Freazonspeech-k2-v2-ja-en%2Fresolve%2F12b44671ff48b9aed622551d64e02fea9a2cf870%2Ftokens.txt=&etag=%2257f652979f6cb607837a712f7c28267a1cb3a100%22>
- HTTP：200；最后远端 IP：`198.18.0.192`；完整大小：26631；SHA256：`144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24`。

### reazon / asr/tokens.txt / hf__reazon__tokens.txt.result.json

- 稳定入口：<https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/tokens.txt>
- 最终 URL：<https://huggingface.co/api/resolve-cache/models/csukuangfj/reazonspeech-k2-v2-ja-en/12b44671ff48b9aed622551d64e02fea9a2cf870/tokens.txt?%2Fcsukuangfj%2Freazonspeech-k2-v2-ja-en%2Fresolve%2F12b44671ff48b9aed622551d64e02fea9a2cf870%2Ftokens.txt=&etag=%2257f652979f6cb607837a712f7c28267a1cb3a100%22>
- HTTP：200；最后远端 IP：`198.18.0.192`；完整大小：26631；SHA256：`144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24`。

### sense / asr/model.int8.onnx / continued__ms__sense__model.int8.onnx.result.json

- 稳定入口：<https://modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/73eca47697f980daa3d16112404174b6b950b514/model.int8.onnx>
- 最终 URL：<https://cdn-lfs-cn-1.modelscope.cn/prod/lfs-objects/c7/1f/0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51?filename=model.int8.onnx&namespace=pengzhendong&repository=sherpa-onnx-sense-voice-zh-en-ja-ko-yue&revision=73eca47697f980daa3d16112404174b6b950b514&tag=model&auth_key=1792036549-e2e7607c11f74130ac358f08c0501d8f-0-1e5fb19851571cfebaeaf3cd986e94ea>
- HTTP：206；最后远端 IP：`198.18.1.214`；完整大小：239233841；SHA256：`c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`。

### sense / asr/model.int8.onnx / mirror__sense__model.int8.onnx.result.json

- 稳定入口：<https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/model.int8.onnx>
- 最终 URL：<https://us.aws.cdn.hf.co/xet-bridge-us/6697e8e13ecf7c46ebb4242b/c45ba1d6a13329c4aca1dc118cabdc643ca09cb8192abb979648dd68f9917323?response-content-disposition=inline%3B+filename*%3DUTF-8%27%27model.int8.onnx%3B+filename%3D%22model.int8.onnx%22%3B&xip=eKqk3OAD6Bs&user_id=public&X-Xet-Cas-Uid=public&Expires=1792039926&Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly91cy5hd3MuY2RuLmhmLmNvL3hldC1icmlkZ2UtdXMvNjY5N2U4ZTEzZWNmN2M0NmViYjQyNDJiL2M0NWJhMWQ2YTEzMzI5YzRhY2ExZGMxMThjYWJkYzY0M2NhMDljYjgxOTJhYmI5Nzk2NDhkZDY4Zjk5MTczMjNcXD9yZXNwb25zZS1jb250ZW50LWRpc3Bvc2l0aW9uPWlubGluZSUzQitmaWxlbmFtZSUyQSUzRFVURi04JTI3JTI3bW9kZWwuaW50OC5vbm54JTNCK2ZpbGVuYW1lJTNEJTIybW9kZWwuaW50OC5vbm54JTIyJTNCJnhpcD1lS3FrM09BRDZCcyZ1c2VyX2lkPXB1YmxpYyZYLVhldC1DYXMtVWlkPXB1YmxpYyIsIkNvbmRpdGlvbiI6eyJEYXRlTGVzc1RoYW4iOnsiRXBvY2hUaW1lIjoxNzkyMDM5OTI2fX19XX0_&Signature=MEQCIBcYdi%7E%7EbFa16y8RPpBLnPOifiqH-yIAVoSw06hnZMTnAiBFkXPj0GOIb8iS1x9K9aObbOUrk1ZNzuPXZqqWa3YOqw__&Key-Pair-Id=01KXEF4KZ1B6FV465MAWR4M21F&Hash-Algorithm=SHA256>
- HTTP：200；最后远端 IP：`198.18.1.204`；完整大小：239233841；SHA256：`c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`。

### sense / asr/model.int8.onnx / hf__sense__model.int8.onnx.result.json

- 稳定入口：<https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/model.int8.onnx>
- 最终 URL：<https://us.aws.cdn.hf.co/xet-bridge-us/6697e8e13ecf7c46ebb4242b/c45ba1d6a13329c4aca1dc118cabdc643ca09cb8192abb979648dd68f9917323?user_id=public&response-content-disposition=inline%3B+filename*%3DUTF-8%27%27model.int8.onnx%3B+filename%3D%22model.int8.onnx%22%3B&xip=eKqk3OAD6Bs&X-Xet-Cas-Uid=public&Expires=1792039917&Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly91cy5hd3MuY2RuLmhmLmNvL3hldC1icmlkZ2UtdXMvNjY5N2U4ZTEzZWNmN2M0NmViYjQyNDJiL2M0NWJhMWQ2YTEzMzI5YzRhY2ExZGMxMThjYWJkYzY0M2NhMDljYjgxOTJhYmI5Nzk2NDhkZDY4Zjk5MTczMjNcXD91c2VyX2lkPXB1YmxpYyZyZXNwb25zZS1jb250ZW50LWRpc3Bvc2l0aW9uPWlubGluZSUzQitmaWxlbmFtZSUyQSUzRFVURi04JTI3JTI3bW9kZWwuaW50OC5vbm54JTNCK2ZpbGVuYW1lJTNEJTIybW9kZWwuaW50OC5vbm54JTIyJTNCJnhpcD1lS3FrM09BRDZCcyZYLVhldC1DYXMtVWlkPXB1YmxpYyIsIkNvbmRpdGlvbiI6eyJEYXRlTGVzc1RoYW4iOnsiRXBvY2hUaW1lIjoxNzkyMDM5OTE3fX19XX0_&Signature=MEQCICRFEMff0PmG-FkAMJnVSBEKQdapC7fBSlXjuaYUSFGBAiAGV9InwZdYMxycROPrktYuexZidpbVJstLF%7EH0w8HD-Q__&Key-Pair-Id=01KXEF4KZ1B6FV465MAWR4M21F&Hash-Algorithm=SHA256>
- HTTP：200；最后远端 IP：`198.18.1.204`；完整大小：239233841；SHA256：`c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`。

### sense / asr/tokens.txt / ms__sense__tokens.txt.result.json

- 稳定入口：<https://modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/73eca47697f980daa3d16112404174b6b950b514/tokens.txt>
- 最终 URL：<https://modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/73eca47697f980daa3d16112404174b6b950b514/tokens.txt>
- HTTP：200；最后远端 IP：`198.18.1.202`；完整大小：315894；SHA256：`f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`。

### sense / asr/tokens.txt / mirror__sense__tokens.txt.result.json

- 稳定入口：<https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/tokens.txt>
- 最终 URL：<https://huggingface.co/api/resolve-cache/models/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/2365baeacb507f821a0c8120fcee3d484dba7a07/tokens.txt?%2Fcsukuangfj%2Fsherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17%2Fresolve%2F2365baeacb507f821a0c8120fcee3d484dba7a07%2Ftokens.txt=&etag=%222cfc92fc2ff26aaa690b7c01fd96b41109413881%22>
- HTTP：200；最后远端 IP：`198.18.0.192`；完整大小：315894；SHA256：`f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`。

### sense / asr/tokens.txt / hf__sense__tokens.txt.result.json

- 稳定入口：<https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/tokens.txt>
- 最终 URL：<https://huggingface.co/api/resolve-cache/models/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/2365baeacb507f821a0c8120fcee3d484dba7a07/tokens.txt?%2Fcsukuangfj%2Fsherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17%2Fresolve%2F2365baeacb507f821a0c8120fcee3d484dba7a07%2Ftokens.txt=&etag=%222cfc92fc2ff26aaa690b7c01fd96b41109413881%22>
- HTTP：200；最后远端 IP：`198.18.0.192`；完整大小：315894；SHA256：`f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`。

### silero / vad/silero_vad.onnx / ms__vad__silero_vad.onnx.result.json

- 稳定入口：<https://modelscope.cn/models/xnnehang/k2-fsa-silero-vad/resolve/e64bab140550f46d724405a77ce842d2095feeef/silero_vad.onnx>
- 最终 URL：<https://cdn-lfs-cn-1.modelscope.cn/prod/lfs-objects/9e/24/49e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6?filename=silero_vad.onnx&namespace=xnnehang&repository=k2-fsa-silero-vad&revision=e64bab140550f46d724405a77ce842d2095feeef&tag=model&auth_key=1792036592-67e8f01c7553494da029db3d197f768e-0-ac82bc00b76d6e4778615bee540f0813>
- HTTP：200；最后远端 IP：`198.18.1.214`；完整大小：643854；SHA256：`9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`。

### silero / vad/silero_vad.onnx / mirror__vad__silero_vad.onnx.result.json

- 稳定入口：<https://hf-mirror.com/mobilebytesensei/betterflow-silero-vad/resolve/0ae41137f2cd00de5e8a286fe01bdd5e3644f2da/silero_vad.onnx>
- 最终 URL：<https://us.aws.cdn.hf.co/xet-bridge-us/6a60f4e61e5e6d576a3de9c2/9873141d5fcf5eea4595e486174aea54998d6077028b855df039d9cb7b3136ac?response-content-disposition=inline%3B+filename*%3DUTF-8%27%27silero_vad.onnx%3B+filename%3D%22silero_vad.onnx%22%3B&X-Xet-Cas-Uid=public&xip=eKqk3OAD6Bs&user_id=public&Expires=1792040193&Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly91cy5hd3MuY2RuLmhmLmNvL3hldC1icmlkZ2UtdXMvNmE2MGY0ZTYxZTVlNmQ1NzZhM2RlOWMyLzk4NzMxNDFkNWZjZjVlZWE0NTk1ZTQ4NjE3NGFlYTU0OTk4ZDYwNzcwMjhiODU1ZGYwMzlkOWNiN2IzMTM2YWNcXD9yZXNwb25zZS1jb250ZW50LWRpc3Bvc2l0aW9uPWlubGluZSUzQitmaWxlbmFtZSUyQSUzRFVURi04JTI3JTI3c2lsZXJvX3ZhZC5vbm54JTNCK2ZpbGVuYW1lJTNEJTIyc2lsZXJvX3ZhZC5vbm54JTIyJTNCJlgtWGV0LUNhcy1VaWQ9cHVibGljJnhpcD1lS3FrM09BRDZCcyZ1c2VyX2lkPXB1YmxpYyIsIkNvbmRpdGlvbiI6eyJEYXRlTGVzc1RoYW4iOnsiRXBvY2hUaW1lIjoxNzkyMDQwMTkzfX19XX0_&Signature=MEQCICKjw6uXrpBAKp6wX9DfL5klfXZgzD7Adk2KCl7JYxgsAiBFmOvn0-JoP9xdngNPHYXQm5fLkyullWThY3j9ArnACg__&Key-Pair-Id=01KXEF4KZ1B6FV465MAWR4M21F&Hash-Algorithm=SHA256>
- HTTP：200；最后远端 IP：`198.18.1.204`；完整大小：643854；SHA256：`9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`。

### silero / vad/silero_vad.onnx / github__silero_vad.onnx.result.json

- 稳定入口：<https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx>
- 最终 URL：<https://release-assets.githubusercontent.com/github-production-release-asset/531380835/22cdfd63-e487-4d8d-b836-b3644c7c9451?sp=r&sv=2018-11-09&sr=b&spr=https&se=2026-10-15T04%3A49%3A23Z&rscd=attachment%3B+filename%3Dsilero_vad.onnx&rsct=application%2Foctet-stream&skoid=96c2d410-5711-43a1-aedd-ab1947aa7ab0&sktid=398a6654-997b-47e9-b12b-9515b896b4de&skt=2026-10-15T03%3A48%3A40Z&ske=2026-10-15T04%3A49%3A23Z&sks=b&skv=2018-11-09&sig=wF9CKkOc17tZDkjKQADcoDVdqSYMZCEb4Tpj6OeaATc%3D&jwt=eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJnaXRodWIuY29tIiwiYXVkIjoicmVsZWFzZS1hc3NldHMuZ2l0aHVidXNlcmNvbnRlbnQuY29tIiwia2V5Ijoia2V5MSIsImV4cCI6MTc5MjAzNjYyOCwibmJmIjoxNzkyMDM2MzI4LCJwYXRoIjoicmVsZWFzZWFzc2V0cHJvZHVjdGlvbi5ibG9iLmNvcmUud2luZG93cy5uZXQifQ.JU41wfYd9BkeQNB4Z4L2oc83TUaD8vcWfjX8PV2cPI8&response-content-disposition=attachment%3B%20filename%3Dsilero_vad.onnx&response-content-type=application%2Foctet-stream>
- HTTP：200；最后远端 IP：`185.199.109.133`；完整大小：643854；SHA256：`9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`。



## 最终物理出口完整补测（2026-10-15）

主代理显式绑定 `en1`、禁用显式代理，为入口和每个重定向主机指定通过DNS HTTPS查询得到的真实IP。没有通过198.18.*假IP传输。7个独特文件完整大小和SHA-256均与上方清单一致：

| 文件 | 完整字节数 | 完整传输耗时 | 最终远端 | 本地出口 |
| --- | ---: | ---: | --- | --- |
| SenseVoice int8 | 239233841 | 22.131秒 | 111.123.55.6（ModelScope CDN） | 192.168.110.213 |
| Reazon encoder | 70876409 | 7.360秒 | 111.123.55.6（ModelScope CDN） | 192.168.110.213 |
| Reazon decoder | 5165084 | 7.780秒 | 18.238.96.96（cas-bridge.xethub.hf.co） | 192.168.110.213 |
| Reazon joiner | 1033417 | 完整下载成功 | ModelScope真实IP | 192.168.110.213 |
| Reazon tokens | 26631 | 完整下载成功 | ModelScope真实IP | 192.168.110.213 |
| SenseVoice tokens | 315894 | 完整下载成功 | ModelScope真实IP | 192.168.110.213 |
| Silero VAD | 643854 | 0.637秒（首轮物理验证） | 111.123.55.6（ModelScope CDN） | 192.168.110.213 |

证据：`.qa/direct-asr/*-transfer.json`、完整文件与SHA-256，以及`.qa/model-source-check/direct-vad-transfer.json`。Decoder第一次未为新重定向主机提供真实IP而超时；补充`cas-bridge.xethub.hf.co:443:18.238.96.96`后成功。HF-mirror仍可能跳转海外CDN，不能把国内入口等同于境内全程。这是本机当前网络的真实直连结果，不能推及所有国内运营商或未来可用性。
