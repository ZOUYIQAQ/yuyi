# 文字翻译供应商模板

在「设置 → 翻译服务 → 添加翻译服务」中选择模板，也可选择「自定义兼容接口」。支持国内、海外分组与搜索。模板只填入地址和示例模型；保存后才能在首页使用，所有字段均可编辑。

平台密钥集中保存，同一平台可添加多份命名密钥，每个服务独立选择。示例模型不代表账户已开通权限；模型输入框可展开平台预设，也可直接输入账户模型或部署 ID；已有自定义值原样保留。

以下地址按官方兼容文档核对于 2026-10-17。模板路径经过程序测试，未逐家进行付费调用。

| 供应商 | 服务地址 | 预设文字模型（均可手动修改） | 官方文档 |
|---|---|---|---|
| DeepSeek 官方 | `https://api.deepseek.com/v1/chat/completions` | `deepseek-flash`、`deepseek-v4-pro` | [模型目录](https://api-docs.deepseek.com/api/create-chat-completion/) |
| 阿里百炼 · 千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus`、`qwen-flash`、`qwen3.8-flash`、`qwen3.7-plus`、`qwen3.8-max` | [模型目录](https://help.aliyun.com/zh/model-studio/models) |
| 硅基流动 | `https://api.siliconflow.cn/v1` | `deepseek-ai/DeepSeek-V4-Flash` | [模型目录](https://docs.siliconflow.cn/docs/api/chat-completions-post) |
| 智谱 BigModel | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | `glm-5.3-flash`、`glm-5.3` | [模型目录](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction) |
| 月之暗面 · Kimi | `https://api.moonshot.cn/v1` | `kimi-k3`、`kimi-k2.6` | [模型目录](https://platform.kimi.com/docs/models) |
| MiniMax 国内 | `https://api.minimax.cn/v1` | `MiniMax-M3`、`MiniMax-M2.7-highspeed`、`MiniMax-M2.7` | [模型目录](https://platform.minimax.cn/docs/api-reference/text-openai-api) |
| 阶跃星辰 | `https://api.stepfun.com/v1` | `step-3.7-flash`、`step-3.5-flash` | [模型目录](https://platform.stepfun.com/docs/zh/guides/models/step-3.7-flash) |
| 火山方舟 · 豆包 | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` | `doubao-seed-2-1-pro-260915` | [模型目录](https://docs.volcengine.com/docs/ark/context-management?lang=zh) |
| 百度智能云 · 千帆 | `https://qianfan.baidubce.com/v2/chat/completions` | `ernie-4.5-turbo-128k` | [模型目录](https://cloud.baidu.com/product-s/qianfan_home) |
| 腾讯云 · 混元 | `https://api.hunyuan.cloud.tencent.com/v1` | `hunyuan-turbos-latest` | [模型目录](https://cloud.tencent.com/document/product/1729/111007) |
| Anthropic · Claude | `https://api.anthropic.com/v1` | `claude-haiku-5-5`、`claude-sonnet-5-5`、`claude-opus-5-5` | [模型目录](https://platform.claude.com/docs/en/models/overview) |
| OpenAI | `https://api.openai.com/v1` | `gpt-6-luna`、`gpt-6.1-sol`、`gpt-6-astra` | [模型目录](https://developers.openai.com/api/docs/models/gpt-6-luna) |
| Google · Gemini | `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions` | `gemini-3.8-flash`、`gemini-3.5-flash-lite`、`gemini-3.6-flash` | [模型目录](https://ai.google.dev/gemini-api/docs/models) |
| OpenRouter | `https://openrouter.ai/api/v1` | `google/gemini-3.8-flash`、`deepseek/deepseek-v4.1-flash`、`z-ai/glm-5.3-flash` | [模型目录](https://openrouter.ai/api/v1/models) |
| xAI · Grok | `https://api.x.ai/v1` | `grok-4.7` | [模型目录](https://docs.x.ai/developers/models) |
| Groq | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile`、`llama-3.1-8b-instant`、`openai/gpt-oss-20b`、`openai/gpt-oss-120b` | [模型目录](https://console.groq.com/docs/models) |
| Mistral AI | `https://api.mistral.ai/v1` | `mistral-small-latest`、`mistral-large-latest` | [模型目录](https://docs.mistral.ai/models) |
| Together AI | `https://api.together.ai/v1` | `MiniMaxAI/MiniMax-M3`、`deepseek-ai/DeepSeek-V4.1-Flash`、`openai/gpt-oss-120b` | [模型目录](https://docs.together.ai/docs/serverless/models) |
| Fireworks AI | `https://api.fireworks.ai/inference/v1` | `accounts/fireworks/models/glm-5p3-flash`、`accounts/fireworks/models/glm-5p3`、`accounts/fireworks/models/kimi-k3` | [模型目录](https://docs.fireworks.ai/getting-started/quickstart) |
| DeepInfra | `https://api.deepinfra.com/v1/openai/chat/completions` | `deepseek-ai/DeepSeek-V4.1-Flash`、`zai-org/GLM-5.3-Flash`、`meta-llama/Llama-3.3-70B-Instruct-Turbo` | [模型目录](https://api.deepinfra.com/models/list) |
| Cerebras | `https://api.cerebras.ai/v1` | `qwen-3.8-27b`、`gpt-oss-120b` | [模型目录](https://inference-docs.cerebras.ai/models/overview) |
| Z.AI 国际 | `https://api.z.ai/api/paas/v4/chat/completions` | `glm-5.3`、`glm-5.3-flash` | [模型目录](https://docs.z.ai/guides/llm/glm-5.3) |
| NVIDIA NIM | `https://integrate.api.nvidia.com/v1` | `nvidia/nemotron-3-super-120b-a12b`、`deepseek-ai/deepseek-v4.1-flash`、`z-ai/glm-5.3-flash` | [模型目录](https://integrate.api.nvidia.com/v1/models) |
| MiniMax 国际 | `https://api.minimax.io/v1` | `MiniMax-M3`、`MiniMax-M2.7-highspeed`、`MiniMax-M2.7` | [模型目录](https://platform.minimax.io/docs/api-reference/text-openai-api) |

火山方舟的模型字段也可填写账户的推理接入点 ID。百炼新业务空间可将地址替换为控制台提供的地域／业务空间地址。Claude 多工作区密钥如需工作区标识，可在附加请求头中填写 `anthropic-workspace-id`。

未列出的供应商或自建服务，只要支持 OpenAI Chat Completions 的文字消息与流式回复，即可手动配置。模型权限、地区与服务商网络均由账户实际情况决定。
