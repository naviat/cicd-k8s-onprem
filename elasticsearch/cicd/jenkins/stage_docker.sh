#!/usr/bin/env bash

set -e;
PREFIX="";
SUFFIX="";

## Help message ##
tool_help () {
  echo "
Usage: ${0##*/} [OPTIONS]

OPTIONS:
  --prefix PREFIX    Prefix for docker image name.
  --suffix SUFFIX    A suffix to be added to the tag.
  --full_build XXX   Ignored by script.
  -h | --help        Print this message and exit.
" 1>&2;
}

## Parse input arguments ##
while [ "$#" -gt 0 ]; do
  case "$1" in
    --prefix )      PREFIX="$2";  shift;  ;;
    --suffix )      SUFFIX="$2";  shift;  ;;
    --full_build )                shift;  ;;
    -h | --help )   tool_help;    exit 0; ;;
    * )             tool_help;    exit 1; ;;
  esac
  shift;
done
NAME=elasticsearch
VERSION=$(sed -n "/^current_version/{ s|.*= ||; p; }" ./.bumpversion.cfg)
docker build \
  -t "${PREFIX}${NAME}:${VERSION}${SUFFIX}" \
  -f ./cicd/docker/Dockerfile .\
  1>&2;

echo "${PREFIX}${NAME}:${VERSION}${SUFFIX}"
