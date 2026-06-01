# Stellnula 数据模型设计

Stellnula 的数据模型以配置定义、配置作用域和配置发布为核心。配置中心需要同时支撑公共配置、应用配置，以及后续作为服务治理规则底座时的路由、限流、熔断和降级规则发布。

## 1. Problem analysis

配置分类不能把环境、文件、敏感配置和治理属性全部并列为业务类型。更稳定的模型是：

- 配置归属决定配置属于公共域还是某个应用。
- 配置作用域决定配置在哪个环境、地域、可用区、集群、namespace 和 group 生效。
- 配置形态决定配置内容是 KV 还是 FILE。
- 配置安全属性决定是否需要加密、脱敏和更严格审计。
- 配置治理属性决定是否参与灰度、版本、审计、回滚和审批。

公共配置的环境是强隔离边界，允许跨环境手动复制发布，但客户端不允许跨环境直接读取。手动复制必须生成目标环境自己的发布版本、审计记录和回滚点。

## 2. Design

### 2.1 配置分类

公共配置：

```text
公共配置
 ├── clusterList: ["default", "gray"]
 ├── zoneList: ["sg-a", "sg-b"]
 ├── contentType: KV / FILE
 ├── sensitive: true / false
 └── data: ...
```

应用配置：

```text
应用配置
 ├── appId: order-service
 ├── envList: ["prod"]
 ├── clusterList: ["default"]
 ├── zoneList: ["sg-a"]
 ├── contentType: KV / FILE
 └── data: ...
```

公共配置可以面向多个应用、多个 SDK 或多个服务共享。应用配置只归属于明确的 `appId`，不能被其他应用隐式继承。

### 2.2 配置属性

```text
配置归属：应用配置 / 公共配置
配置作用域：env / region / zone / cluster / namespace / group
配置形态：KV / FILE
配置安全属性：普通配置 / 敏感配置
配置治理属性：灰度 / 版本 / 审计 / 回滚 / 审批
```

环境规则：

- `env` 是强隔离边界。
- 客户端读取必须携带自身 `env`。
- 服务端只允许在同一个 `env` 内进行精确匹配或继承匹配。
- 公共配置允许跨环境手动复制发布。
- 跨环境复制必须产生目标环境独立的 release、revision、audit log 和 rollback point。
- 客户端禁止从 `prod` 回退读取 `staging`、`test` 或 `dev` 配置。

### 2.3 基本对象模型

```text
ConfigDefinition
  - configId
  - configName
  - ownerType: APPLICATION / PUBLIC
  - ownerId: appId / publicNamespace
  - contentType: KV / FILE
  - sensitive: true / false

ConfigScope
  - configId
  - env
  - region
  - zone
  - cluster
  - scopeMode: EXACT / INHERITABLE / REPLICATED

ConfigRelease
  - configId
  - scopeId
  - version
  - content
  - checksum
  - releaseStatus
  - createdBy
  - releasedAt
```

### 2.4 关系说明

- `ConfigDefinition` 是配置的逻辑定义，不直接代表某个环境中的可读配置。
- `ConfigScope` 绑定配置定义和生效范围。
- `ConfigRelease` 是客户端可读取内容的来源，只读取已发布状态。
- 多个 `ConfigScope` 可以指向同一个 `ConfigDefinition`。
- 每个 `ConfigScope` 可以拥有多个 `ConfigRelease` 历史版本。
- 当前有效配置由 scope 内最新 `PUBLISHED` release 决定。

## 3. Implementation

### 3.1 PostgreSQL 表边界

核心表建议：

- `stn_config_definition`：配置定义。
- `stn_config_scope`：配置作用域。
- `stn_config_release`：配置发布版本。
- `stn_config_release_audit`：发布审计。
- `stn_client_snapshot`：客户端同步状态。

### 3.2 作用域匹配顺序

客户端读取时只允许在同一 `env` 内匹配：

```text
env + region + zone + cluster + namespace + group
env + region + zone + default cluster + namespace + group
env + region + default zone + default cluster + namespace + group
env + default region + default zone + default cluster + namespace + group
env + default region + default zone + default cluster + default namespace + default group
```

`scopeMode` 语义：

- `EXACT`：只允许精确作用域命中。
- `INHERITABLE`：允许同一环境内按匹配顺序继承。
- `REPLICATED`：从其他环境或作用域手动复制而来，但在当前环境内拥有独立 release。

### 3.3 内容形态

KV 配置：

- 适合单配置项、开关、阈值、连接参数、线程池参数。
- `content` 可以保存字符串值或结构化 JSON。
- `configName` 与 `ownerId + namespace + group` 共同表达业务含义。

FILE 配置：

- 适合 `application.yaml`、`logback.xml`、路由规则文件和较完整策略文件。
- 小文件可直接保存 `content`。
- 大文件后续可以保存对象存储引用、checksum 和 content length。

## 4. Complete code

该模型对应 README 中的 PostgreSQL 设计。后续落库时要保证：

- 所有字段使用明确列名，禁止 `select *`。
- 所有核心表和字段都添加 PostgreSQL comment。
- 读取路径只读取已发布 release。
- 客户端跨环境读取在服务端校验层直接拒绝。
- 手动复制发布必须生成新的目标环境 revision。
