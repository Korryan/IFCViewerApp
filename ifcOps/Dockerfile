FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PIP_NO_CACHE_DIR=1
ENV IFC_OPS_DATA_ROOT=/data

WORKDIR /app

COPY ifcOps/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY ifcOps/app ./app

EXPOSE 8090

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
