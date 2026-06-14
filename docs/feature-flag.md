# Stellnula Feature Flag 设计

Feature Flag 是应用配置的一种特殊使用形态。它不是公共配置，也不是服务治理规则；它归属于明确的应用 `appId`，用于在不重新发布应用代码的情况下控制功能开关、实验分流、渐进开放和快速回滚。

## 1. Problem analysis

当前 Stellnula 已经形成三类控制面配置边界：

- 应用配置：`owner_type = APPLICATION`，`namespace = app-config`
- 公共配置：`owner_type = PUBLIC`，`namespace = common-config`
- 治理规则：`namespace = governance`，`group = service-governance`

Feature Flag 应该归入应用配置，而不是新增独立的顶层配置归属类型。原因是：

- Feature Flag 通常只对某个应用生效，归属边界是 `appId`。
- Feature Flag 需要跟应用配置共享环境、地域、可用区、集群、发布、审计、回滚和客户端订阅能力。
- Feature Flag 的运行时读取对象是应用客户端实例，而不是所有应用共享的公共配置。
- Feature Flag 和灰度配置都涉及按客户端上下文决策，但二者语义不同：灰度配置返回不同配置内容，Feature Flag 返回某个功能开关的评估结果。

因此，Feature Flag 不应放在 `common-config`，也不建议放在 `governance`。推荐放在：

```text
owner_type = APPLICATION
owner_id   = {appId}
namespace  = app-config
group      = feature-flags
```

`group = feature-flags` 表示这是应用配置域中的特性开关分组。客户端可以按 `app-config/feature-flags` 单独订阅，也可以后续通过多 namespace/group 订阅能力和普通应用配置一起拉取。

## 2. Design

### 2.1 目标

Feature Flag 需要满足以下目标：

1. 支持布尔开关、枚举变体、字符串/数字参数等不同类型。
2. 支持默认值，保证规则不命中或配置不可用时有确定结果。
3. 支持按客户端上下文规则评估，例如 `clientId`、`clientIp`、标签、百分比。
4. 支持发布、回滚、审计、版本和缓存刷新。
5. 支持客户端本地缓存，控制面或数据面短暂不可用时不影响已发布开关读取。
6. 不与灰度配置表强绑定；Feature Flag 的规则结构由配置内容表达，灰度配置仍负责“一主多灰”的配置内容覆盖。

### 2.2 配置维度

Feature Flag 使用现有配置定义模型：

```text
config_id     = feature.{flagKey}
config_name   = {flagKey}.json
config_key    = {config_name}
owner_type    = APPLICATION
owner_id      = {appId}
namespace     = app-config
group         = feature-flags
config_format = json
content_type  = FILE
```

示例：

```text
config_id     = feature.checkout-new-flow
config_name   = checkout-new-flow.json
config_key    = checkout-new-flow.json
owner_type    = APPLICATION
owner_id      = checkout-service
namespace     = app-config
group         = feature-flags
config_format = json
content_type  = FILE
```

这样设计后，同一应用、同一 namespace、同一 group 下仍然由 `config_name` 唯一约束保护，避免重复定义同名 Feature Flag。`config_id` 是服务端内部稳定标识，可用于精确更新、删除和订阅过滤；`config_key` 是服务端下发给客户端的配置键，当前实现来自 `config_name`，更适合被客户端用作本地文件名。

推荐第一阶段采用“单个 Feature Flag 对应一个配置文件”的模型。它的优点是发布、回滚、审计和订阅粒度更细，一个开关变更不会导致整个应用的所有开关一起下发。对于上百个开关的应用，可以再按业务模块增加 group，例如：

```text
namespace = app-config
group     = feature-flags.checkout
config_key = checkout-new-flow.json

namespace = app-config
group     = feature-flags.payment
config_key = payment-risk-control.json
```

不建议把所有 Feature Flag 长期堆在一个 `feature-flags.json` 中。单文件模型初期实现简单，但当开关数量较多时，每次修改一个 flag 都会导致整份文件重新下发，客户端 diff、审计展示和冲突处理都会变重。只有在客户端强依赖“一次性加载完整开关集合”时，才建议按模块聚合为少量文件，而不是全应用一个大文件。

### 2.3 内容模型

Feature Flag 内容建议统一使用 JSON，方便服务端和 SDK 进行结构化校验与评估。

布尔开关示例：

```json
{
  "key": "checkout-new-flow",
  "type": "BOOLEAN",
  "enabled": true,
  "defaultValue": false,
  "rules": [
    {
      "name": "internal-users",
      "conditions": [
        {
          "attribute": "labels.userType",
          "op": "IN",
          "values": ["internal", "qa"]
        }
      ],
      "value": true
    }
  ]
}
```

多变体示例：

```json
{
  "key": "recommendation-algorithm",
  "type": "VARIANT",
  "enabled": true,
  "defaultValue": "baseline",
  "variants": [
    {
      "key": "baseline",
      "weight": 80
    },
    {
      "key": "rank-v2",
      "weight": 20
    }
  ],
  "rules": [
    {
      "name": "beta-users",
      "conditions": [
        {
          "attribute": "labels.beta",
          "op": "EQ",
          "value": "true"
        }
      ],
      "value": "rank-v2"
    }
  ],
  "rollout": {
    "type": "PERCENTAGE",
    "bucketBy": "clientId",
    "salt": "recommendation-algorithm:v1"
  }
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `key` | Feature Flag 业务键，应与 `config_name` 主体保持一致 |
| `type` | `BOOLEAN`、`VARIANT`、`STRING`、`NUMBER` |
| `enabled` | 总开关，关闭时直接返回 `defaultValue` |
| `defaultValue` | 默认返回值 |
| `rules` | 命中规则，按数组顺序评估 |
| `conditions` | 规则条件，可基于客户端上下文和标签 |
| `variants` | 多变体候选值和比例 |
| `rollout` | 稳定分桶策略 |

### 2.4 规则评估

Feature Flag 评估顺序建议固定为：

```text
读取 flag 配置
  -> enabled=false 返回 defaultValue
  -> 按 rules 顺序匹配
  -> 命中规则返回规则 value
  -> 未命中且存在 rollout 时执行稳定分桶
  -> 未命中返回 defaultValue
```

客户端上下文可复用现有 `ClientContext`：

```text
appId
clientId
env
region
zone
cluster
namespace
group
clientIp
labels
```

比例发布必须使用稳定哈希，不能每次请求随机。推荐分桶输入：

```text
flagKey + ":" + salt + ":" + bucketByValue
```

例如：

```text
checkout-new-flow:v1:client-001
```

这样同一客户端在不同请求、不同服务端实例上得到相同评估结果。

### 2.5 与灰度配置的关系

Feature Flag 和灰度配置都可以按客户端上下文生效，但它们解决的问题不同：

| 能力 | 目标 | 返回结果 | 生命周期 |
| --- | --- | --- | --- |
| Feature Flag | 控制功能是否开启或选择哪个变体 | 开关值或变体值 | 可长期存在 |
| 灰度配置 | 临时验证一份配置内容 | 整份配置内容 | 临时发布过程 |

Feature Flag 不应复用 `config_gray_rule` 表作为主存储。原因是 `config_gray_rule` 表表达的是“同一配置项的灰度内容覆盖”，而 Feature Flag 表达的是“一个功能开关的评估策略”。二者可以共享评估器能力，例如条件匹配和稳定百分比分桶，但不应共享生命周期表。

### 2.6 订阅模型

Feature Flag 作为应用配置下发，客户端订阅建议使用：

```text
namespace = app-config
group     = feature-flags
subscriptionType = CONFIG
subscriptionKey  = *
```

如果客户端只关心某一个开关，可以订阅：

```text
namespace = app-config
group     = feature-flags
subscriptionType = CONFIG
subscriptionKey  = feature.checkout-new-flow
```

这里的 `subscriptionKey` 当前匹配的是 `config_id`，不是下发给客户端的 `config_key`。因此：

```text
config_id       = feature.checkout-new-flow
config_key      = checkout-new-flow.json
subscriptionKey = feature.checkout-new-flow
```

客户端落本地文件时不应只使用 `config_key`，否则不同 namespace 或 group 下同名文件可能冲突。建议本地缓存路径使用：

```text
{appId}/{env}/{namespace}/{group}/{config_key}
```

例如：

```text
checkout-service/prod/app-config/feature-flags/checkout-new-flow.json
checkout-service/prod/app-config/feature-flags.payment/payment-risk-control.json
```

这样既能让客户端按文件拆分缓存，也能保留配置中心的隔离维度。客户端读取 Feature Flag 时可以先扫描 `app-config/feature-flags*` 下的 JSON 文件，构建本地 `flagKey -> flagDefinition` 索引，再对业务调用执行本地评估。

当前 `ClientContext` 一次请求只有一个 `namespace/group`，所以客户端如果同时需要普通应用配置和 Feature Flag，有两种方案：

1. 短期方案：分别拉取 `app-config/default` 和 `app-config/feature-flags`。
2. 长期方案：扩展订阅模型，让 `ClientSubscriptionFilter` 支持 `namespaceCode`，允许一个 watch 连接订阅多个 namespace/group。

短期不建议把 Feature Flag 塞进 `group=default`，否则普通应用配置文件和开关策略会混在一起，客户端本地文件缓存和控制台分类都会变得不清晰。

## 3. Implementation

### 3.1 控制面接口

建议新增独立控制器：

```text
/api/v1/control-plane/feature-flags
```

接口建议：

```text
GET    /api/v1/control-plane/feature-flags
GET    /api/v1/control-plane/feature-flags/{flagKey}
POST   /api/v1/control-plane/feature-flags
PUT    /api/v1/control-plane/feature-flags/{flagKey}
POST   /api/v1/control-plane/feature-flags/{flagKey}/publish
DELETE /api/v1/control-plane/feature-flags/{flagKey}
```

请求体建议：

```json
{
  "id": "feature.checkout-new-flow",
  "appId": "checkout-service",
  "key": "checkout-new-flow",
  "description": "New checkout flow rollout",
  "environment": "prod",
  "cluster": "default",
  "enabled": true,
  "type": "BOOLEAN",
  "defaultValue": false,
  "rules": [],
  "updatedBy": "xiaoy"
}
```

控制器转换为 `ConfigMutationCommand`：

```text
configId      = request.id or "feature." + request.key
configName    = request.key + ".json"
ownerType     = APPLICATION
ownerId       = request.appId
namespaceCode = app-config
groupCode     = feature-flags
format        = json
contentType   = FILE
content       = normalized feature flag JSON
```

### 3.2 服务端校验

Feature Flag 写入前应校验：

- `key` 不能为空，只允许字母、数字、点、短横线、下划线。
- `type` 必须是 `BOOLEAN`、`VARIANT`、`STRING`、`NUMBER`。
- `defaultValue` 类型必须与 `type` 匹配。
- `rules[].value` 类型必须与 `type` 匹配。
- `variants[].key` 不能重复。
- 百分比权重总和不能超过 100。
- `rollout.bucketBy` 必须来自稳定上下文字段，例如 `clientId`、`clientIp`、`labels.xxx`。

校验通过后应将 JSON 标准化，保证字段顺序和默认字段稳定，便于 checksum、审计和 diff 展示。

### 3.3 DB 落库

第一阶段可以不新增独立 Feature Flag 表，直接复用现有配置表：

- `config_definition` 保存 flag 定义。
- `config_scope` 保存环境、地域、可用区、集群。
- `config_release` 保存 flag JSON 内容。
- `change_event` 驱动服务端缓存增量刷新。
- `client_subscription` 记录客户端对 `app-config/feature-flags` 的订阅。

如果后续需要在控制台按 `enabled`、`type`、`targetLabel`、`rolloutPercentage` 等字段高频查询，可以再新增索引表：

```text
feature_flag_index
  - config_id
  - scope_id
  - release_id
  - revision
  - owner_id
  - env
  - group_code
  - flag_key
  - flag_type
  - enabled
  - default_value
  - rollout_type
  - rollout_percentage
  - updated_at
```

该索引表只作为查询加速，不作为事实来源。事实来源仍然是 `config_release.content`。

### 3.4 数据面与 SDK

数据面第一阶段可以把 Feature Flag 当普通配置下发，客户端 SDK 本地完成评估：

```text
服务端下发 flag JSON
SDK 缓存到本地
业务调用 sdk.boolValue("checkout-new-flow", false)
SDK 使用本地 ClientContext 和 labels 评估规则
```

这种方式有两个优点：

- 运行时评估不依赖服务端请求，延迟低。
- 服务端只负责配置分发和版本一致性，复杂度可控。

后续如果需要服务端集中评估，可以新增：

```text
POST /api/v1/feature-flags/evaluate
```

但不建议第一阶段就引入服务端实时评估，否则每次业务判断都可能产生远程调用。

### 3.5 变更传播

Feature Flag 发布后复用当前配置发布链路：

```text
控制面写 config_release
  -> 写 change_event
  -> 当前实例立即刷新增量缓存
  -> 其他 stellnula-service 实例每秒增量拉取
  -> watch 客户端被唤醒或下一次 delta 拉取
  -> SDK 更新本地 flag 缓存
```

由于 Feature Flag 可能影响线上功能开关，建议客户端 SDK 更新缓存时提供变更回调：

```text
onFlagChanged(flagKey, oldValue, newValue)
```

业务方可选择监听关键开关变化，刷新本地派生状态。

## 4. Complete code

后续代码落地建议按以下顺序推进：

1. 新增 `FeatureFlagController`，独立于应用配置文件接口。
2. 新增 `FeatureFlagValidator`，负责 JSON schema 级别校验和标准化。
3. 复用 `ConfigMutationService` 写入 `app-config/feature-flags`。
4. 增加 `FeatureFlagResponse`，展示 `key/type/enabled/defaultValue/rules/version/status`。
5. 增加 SDK 侧 `FeatureFlagClient`，从本地配置快照中读取并评估。
6. 后续按查询需求决定是否新增 `feature_flag_index`。

第一阶段不建议新增独立主表，因为现有配置发布、revision、审计、缓存和 watch 链路已经能承载 Feature Flag。Feature Flag 的差异主要在控制面校验、展示模型和客户端评估 SDK，而不是底层发布存储模型。
