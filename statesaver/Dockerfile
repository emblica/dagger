FROM python:3.5-slim

WORKDIR /
RUN pip install awscli && apt-get update  && apt-get install -y jq && rm -rf /var/lib/apt/lists/*
ADD saver.sh saver.sh
ADD loader.sh loader.sh

CMD ["./saver.sh"]
