#!bin/sh

# Download run state from S3 as full directory tree into /input

aws s3 cp s3://$DAGGER_BUCKET/state/$MANIFEST_NAME/$RUN_NAME/ /input/ --recursive
