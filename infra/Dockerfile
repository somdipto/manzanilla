FROM python:3.12.10-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home orange
COPY cloud cloud
COPY admin admin
RUN mkdir data && chown orange:orange data
USER orange
EXPOSE 8080
CMD ["uvicorn","cloud.app:app","--host","0.0.0.0","--port","8080","--workers","1","--ws-max-size","65536"]
