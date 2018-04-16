#!bin/sh

# Waiting in sleeploop until there is file at /state/ready
# After that copy whole /output directory to S3 recursively and stop

while [ ! -f /state/ready ]
do
  sleep 1
done

aws s3 cp /output/ s3://$DAGGER_BUCKET/state/$MANIFEST_NAME/$RUN_NAME/$TASK_NAME --recursive
