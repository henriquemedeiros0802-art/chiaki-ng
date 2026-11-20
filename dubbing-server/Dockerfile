FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY dubserver ./dubserver
ENV PORT=8765 DUB_DATA=/data
VOLUME /data
EXPOSE 8765
CMD ["python", "-m", "dubserver.app"]
