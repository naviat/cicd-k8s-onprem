#!/usr/bin/env bash

sed -n "/^current_version/{ s|.*= ||; p; }" ./.bumpversion.cfg
