FROM python:3.11-slim

WORKDIR /app

# 时区设置为北京时间
ENV TZ=Asia/Shanghai
RUN apt-get update && apt-get install -y --no-install-recommends tzdata && \
    ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY config.py .
COPY valid_codes.txt .
COPY sources/ ./sources/
COPY stock_collector.py .
COPY db_to_kafka.py .

CMD ["python", "stock_collector.py"]
