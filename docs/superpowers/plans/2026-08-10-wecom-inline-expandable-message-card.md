# 企业微信联系人级消息窗口 Implementation Plan

**状态：等待重写。**

旧计划依赖未经企业微信官方文档确认的 `display-type="text"`，并为每条消息创建独立 OpenDataFrame。真实授权环境已经证明该路线会产生空白消息框，因此旧任务和验收条件全部失效。

当前设计真源为：

`docs/superpowers/specs/2026-08-10-wecom-inline-expandable-message-card-design.md`

用户复核设计后，本文将按联系人级单一 OpenDataFrame、最近 15 条自动加载、官方消息点击详情和确定性资源清理重新编写。
