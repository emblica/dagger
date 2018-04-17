<img src="/docs/dagger_logo.png?raw=true">
# Dagger - Workflow Manager for Kubernetes

Kubernetes has Jobs but it's only limited as single step and not enough for modeling
complex workflows where there is multiple tasks and dependencies between them.

Dagger solves the dependencies and launches Jobs after each other in correct order.
It also transports the data between task steps through `stateloader`-component
which is a little sidekick container running with your workload and handling inputs and outputs.

Dagger is solving same problem than _Makefiles_ are doing in single computer or
other workflow managers such as **Apache Airflow** or **Azkaban** does.
All of these are however not so good option when working with Kubernetes environment.


## Installation
```
kubectl apply -f devops/crd.yml
kubectl apply -f devops/ns.yml
kubectl apply -f devops/rbac.yml
kubectl apply -f devops/dagger.yml
```

## Create Dagger manifest

Create and edit following file (smallflow.yml):
```
apiVersion: "dagger.emblica.fi/v0beta1"
kind: DaggerManifest
metadata:
  name: small-workflow-1
spec:
  tasks:
  - name: load-kubernetes-homepage
    namespace: default
    depends: []
    image: busybox
    command:
    - sh
    - -c
    - "wget -O /output/index.html http://kubernetes.io/index.html && touch /state/ready"
  - name: ps-aux
    namespace: default
    depends:
      - load-kubernetes-homepage
    image: busybox
    command:
    - sh
    - -c
    - "du -hs /input/load-kubernetes-homepage/index.html && touch /state/ready"

```
After that install manifest into cluster:
```
kubectl apply -f smallflow.yml
```
Now the manifest must be launched. Single run is called `DaggerRun`

```
apiVersion: "dagger.emblica.fi/v0beta1"
kind: DaggerRun
metadata:
  name: small-workflow-1-run-1
  labels:
    manifest: small-workflow-1
spec:
  params:
    site: "www.google.fi"

```

You can give any parameters for the run and they are usable inside tasks of the workflow as environment variables.

```
kubectl apply -f run.yml
```



## Stateloader

Stateloader is super simple (really check it out from `statesaver/`) shell-script
that takes care of moving data and results between tasks.

Initial implementation is with Amazon S3 but it is easy to expand
into pretty much anything from FTP-server to REST-api


### How Stateloader works

Before each task starts there is **loader** command that mounts shared volume with task container and the loader.
Because the loading component is started ass _initContainer_ it will completely download all required files until the real workload is started.

```
initContainers:
- name: load-input
  image: docker.io/emblica/dagger-stateloader
  command: ["./loader.sh"]
  volumeMounts:
  - name: state
    mountPath: /state
  - name: input
    mountPath: /input
```

After workload is done **saver** waits for **readyfile** to appear and uploads the shared volumes back into S3.
Because Kubernetes doesn't currently support any other way it's similar container than the actual workload but running wait loop until workload finishes and exits.

When both workload and **saver** exists successfully the Job is done and Dagger will launch next Job


## Todo

Dagger is released as  work in process and it's codebase is mainly from single hackathon,
assume that it's not 'production' ready in that sense.

- Automatic garbage collection (for recurring jobs)
- Improve Kubernetes API handling (current hack uses kubectl through JSON-input/output)
- Trigger support
- Tests
- Static analysis
- Manifest checks (schema, loops etc.)
- Better failure handling
- Better documentation
- Stateloaders for other environment than just for Amazon S3


## Supported by

This work is partially made with support from Emblica.   
https://emblica.com
>Emblica is a data engineering company. Our clients range from startups to publicly listed companies. We support Open Source and commit back to the community whenever we can.
