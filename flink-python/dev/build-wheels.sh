#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
set -e -x

## 1. install python env
dev/lint-python.sh -s py_env

PY_ENV_DIR=`pwd`/dev/.uv/envs
py_env=("3.9" "3.10" "3.11" "3.12")
## 2. install dependency
for ((i=0;i<${#py_env[@]};i++)) do
    source `pwd`/dev/.uv/envs/${py_env[i]}/bin/activate
    echo "Installing dependencies for environment: ${py_env[i]}"
    uv pip install --group dev
    deactivate
done

## 3. build wheels
for ((i=0;i<${#py_env[@]};i++)) do
    echo "Building wheel for environment: ${py_env[i]}"
    if [[ "$(uname)" != "Darwin" ]]; then
        # force the linker to use the older glibc version in Linux
        export CFLAGS="-I. -include dev/glibc_version_fix.h"
    fi
    ${PY_ENV_DIR}/${py_env[i]}/bin/python setup.py bdist_wheel
done

## 4. convert linux_x86_64 wheel to manylinux1 wheel in Linux
if [[ "$(uname)" != "Darwin" ]]; then
    echo "Converting linux_x86_64 wheel to manylinux1"
    source `pwd`/dev/.uv/bin/activate
    # 4.1 install patchelf and auditwheel
    uv pip install patchelf==0.17.2.1 auditwheel==3.2.0
    # 4.2 convert Linux wheel
    for wheel_file in dist/*.whl; do
        auditwheel repair ${wheel_file} -w dist
        rm -f ${wheel_file}
    done
    deactivate
fi
## see the result
ls -al dist/
