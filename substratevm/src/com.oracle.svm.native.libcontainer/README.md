# Native cgroup support for SVM

This contains `libsvm_container`, the native cgroup support for SVM (libsvm_container).
The C code is ported from the OpenJDK and update regularly (see "Updating" below).

## Building

The code is built via `mx build`, so no special actions are required. There is
[`configure.py`](./configure.py), that generates a `build.ninja` that can help with local debugging,
but it is not used by `mx build` and there is no guarantee that it works correctly. Use at your own 
risk. The default `mx build` command only uses settings from `suite.py`, so values in
`build.ninja` or those generated by `configure.py` might be outdated.

## Dependencies

For building `libsvm_container`, a C++14 compiler is required. Some parts of `libsvm_container` depend on `libm`.
At image build time, `native-image` will automatically link statically against `libsvm_container` and `libm`.
No further dependencies are needed at image build or image run time.
Specifically,  `libsvm_container` does _not_ depend on any code from the C++ standard library.

## Code Layout

The file structure is inherited from the OpenJDK. The Files in [`src/hotspot`](./src/hotspot) are
mostly unmodified except for sections guarded with `#ifdef NATIVE_IMAGE`. The Files in
[`src/svm`](./src/svm) are replacements for files that exist in the OpenJDK, but are completely
custom. They only provide the minimal required functionality and are specific to SVM.

## Deviation from Hotspot behavior

On Hotspot, some of the values are cached in native code. Since native caching is undesired
on SVM, we disable it by always returning `true` in `CachedMetric::should_check_metric()`.
To avoid performance penalties compared to Hotspot, SVM caches those values on the Java side.
Currently, this only applies to `cpu_limit` and `memory_limit`. For every update of the
`libsvm_container` code, we should review whether new usages of `CachedMetric` were added.

## Updating

While the code in `libsvm_container` is completely independent and does not need to be in sync with
the OpenJDK, it should be updated regularly to profit from upstream fixes and improvements. To keep
track of this, `ContainerLibrary.java` contains `@BasedOnJDKFile` annotations for each imported file,
which links to the source version in the JDK. With this information, all upstream changes can be
detected. Note that strictly speaking, the referenced version in the annotation does not necessarily
mean that the file was imported from that revision. Rather that all changes have been reviewed. If
there are changes that are irrelevant for `libsvm_container`, we might keep the file as is and still
bump the version. That said, we plan to do full reimports regularly, at least once every for every
release.

To help keeping the `@BasedOnJDKFile` annotations up to date, the
`mx gate --tags check_libcontainer_annotations` command ensures that the actual files and
annotations are in sync.

### Full Reimport

* Remove the C++-namespace from the source code using the
`mx svm_libcontainer_namespace remove` command, in order to minimize the diff to the files from the OpenJDK.
It is recommended to commit the changes, otherwise the namespace will still show in the diff later.

* Replace the files in [`src/hotspot`](./src/hotspot) with those from the OpenJDK.
The `mx reimport-libcontainer-files --jdk-repo path/to/jdk` command can help with that
and will also offer to run `mx svm_libcontainer_namespace remove`.

* Reapply all the changes (`#ifdef` guards) using the diff tool of your choice.
Also adopt the files in [`src/svm`](./src/svm) to provide new functionality, if needed.

* Update the `@BasedOnJDKFile` annotations in `ContainerLibrary.java` to reflect the import revision.

* Reapply the C++-namespaces using the `mx svm_libcontainer_namespace add` command.

## Local Testing

There are various ways for running applications like native images in cgroups.
Also note that many containerization tools such as Docker use cgroups for resource constraints.

### Using CGroup commands

The most basic way of running a program in a cgroup is to create them manually. For example:

```bash
# create cgroup
cgcreate -a <user> -t <user> -g memory,cpu:testgroup
ls -l /sys/fs/cgroup/cpu/testgroup
ls -l /sys/fs/cgroup/memory/testgroup

# set limits
echo 10000000 > /sys/fs/cgroup/memory/testgroup/memory.limit_in_bytes
echo 12000000 > /sys/fs/cgroup/memory/testgroup/memory.soft_limit_in_bytes
echo 100 > /sys/fs/cgroup/cpu/testgroup/cpu.shares

# run image in cgroup
cgexec -g memory,cpu:testgroup ./test

# delete cgroup
cgdelete -g memory,cpu:testgroup
```

### Using systemd

Another possibility is to run a program in a transient systemd scope with resource constraints:

```bash
systemd-run --scope -p MemoryMax=2G --user ./test
```
