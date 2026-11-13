# Kbuild out-of-tree module for the RISC-V SIMT GPU host driver.
#
# Build against a configured kernel tree, e.g.
#   make -C /tmp/linux-build M=$(pwd) ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu-
# which produces gpu_drv.ko.

obj-m += gpu_drv.o
gpu_drv-y := opengpu_drv.o opengpu_hw.o opengpu_memory.o opengpu_mmu.o \
	opengpu_compute.o opengpu_scheduler.o opengpu_admission.o opengpu_tessellation_job.o opengpu_private_snapshot.o opengpu_native_submit.o opengpu_native_job_snapshot.o opengpu_native_graph_alias.o opengpu_native_input_snapshot.o opengpu_native_program_snapshot.o opengpu_native_uniform_snapshot.o opengpu_geometry_uniform_snapshot.o opengpu_geometry_program_snapshot.o opengpu_geometry_snapshot.o opengpu_native_frame_snapshot.o \
	opengpu_drm_device.o opengpu_display.o

ccflags-y := -I$(src)
