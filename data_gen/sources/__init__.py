"""
数据源工厂 — 根据配置返回对应的数据源实例
"""
from sources.jsonl_file import JsonlFileSource
from sources.sina_api import SinaApiSource


def create_source(source_type: str):
    """根据 source_type 创建数据源实例"""
    source_type = source_type.lower()
    if source_type == "sina":
        return SinaApiSource()
    elif source_type == "jsonl":
        return JsonlFileSource()
    else:
        raise ValueError(f"Unknown source_type: {source_type}. Use 'sina' or 'jsonl'")
