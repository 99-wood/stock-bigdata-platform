# cluster — 集群环境模块

## 模块职责

负责 Hadoop、Spark、Kafka、Zookeeper、Redis、MySQL 集群环境的搭建、管理与维护。

## 集群拓扑

| 节点 | IP | 角色 | 组件 |
|------|-----|------|------|
| master0 | 192.168.137.201 | 主节点 | NameNode, ResourceManager, Spark Master |
| slave1 | 192.168.137.202 | 从节点 | DataNode, NodeManager, Spark Worker |
| slave2 | 192.168.137.203 | 从节点 | DataNode, NodeManager, Spark Worker |
| slave3 | 192.168.137.204 | 从节点 | DataNode, NodeManager, Spark Worker |
| mid | 192.168.137.210 | 中间件 | Zookeeper, Kafka, Redis, MySQL |

## 目录结构

```
cluster/
├── sql/
│   └── init.sql                  # MySQL stock_ads 建库建表
├── scripts/
│   ├── cluster-start.sh          # 一键启动全部服务
│   ├── cluster-stop.sh           # 一键停止全部服务
│   ├── cluster-status.sh         # 检查所有服务状态
│   ├── hdfs-init.sh              # HDFS 目录初始化
│   └── kafka-topic-init.sh       # Kafka Topic 创建
├── config/
│   └── quant-weight.properties   # 量化评分因子权重
└── README.md
```

## 组件版本

| 组件 | 版本 | 端口 | 安装路径 |
|------|------|------|----------|
| JDK | 1.8.0_171 | - | /root/jdk1.8.0_171 |
| Hadoop | 2.7.6 | 9000 / 9870 | /root/hadoop-2.7.6 |
| Spark | 2.4.0 | 7077 / 8080 | /root/spark-2.4.0 |
| Kafka | 2.11-2.1.0 | 9092 | /root/kafka |
| Zookeeper | 3.4.13 (Kafka 内置) | 2181 | Kafka 内置 |
| Redis | 7.x | 6379 | apt 安装 |
| MySQL | 5.7+ | 3306 | apt 安装 |

## 连接信息（给团队成员）

| 组件 | 地址 | 端口 | 备注 |
|------|------|------|------|
| HDFS NameNode | hdfs://master0:9000 | 9000 | Spark 读写 |
| YARN WebUI | http://master0:8088 | 8088 | spark-submit |
| Spark Master | spark://master0:7077 | 7077 | |
| Kafka Broker | mid:9092 | 9092 | 采集器推送、Spark 消费 |
| Redis | mid:6379 | 6379 | 密码见配置文件 |
| MySQL | mid:3306 | 3306 | stock_ads 库 |

## 初始化步骤

1. SSH 到 mid，执行 SQL 初始化
2. SSH 到 mid，创建 Kafka topics
3. SSH 到 master0，启动 Hadoop
4. SSH 到 master0，创建 HDFS 目录
5. SSH 到 master0，启动 Spark

## 启动顺序

```
Zookeeper → Kafka → HDFS → YARN → Spark → Redis → MySQL
```
