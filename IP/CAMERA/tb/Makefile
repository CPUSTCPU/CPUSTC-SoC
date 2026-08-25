VERILATOR ?= verilator
BUILD_DIR ?= build
TOP := camera_tb
SIM := $(BUILD_DIR)/V$(TOP)

RTL := \
	../rtl/cpustc_camera_capture.v \
	../rtl/cpustc_camera_axi_writer.v \
	../rtl/cpustc_dvp_rx.v \
	../rtl/axis_async_fifo.v

VERILATOR_FLAGS := \
	--binary \
	--timing \
	--assert \
	-Wall \
	-Wno-fatal \
	-Wno-PINCONNECTEMPTY \
	-Wno-GENUNNAMED \
	-Wno-SELRANGE \
	-Wno-WIDTHEXPAND \
	-Wno-WIDTHTRUNC \
	-Wno-UNUSEDSIGNAL \
	-Wno-UNUSEDPARAM \
	-Wno-SYMRSVDWORD \
	-Wno-BLKSEQ \
	-Wno-SYNCASYNCNET \
	--top-module $(TOP) \
	--Mdir $(BUILD_DIR) \
	-o V$(TOP)

.PHONY: all
all: $(SIM)

$(SIM): camera_tb.sv $(RTL)
	$(VERILATOR) $(VERILATOR_FLAGS) camera_tb.sv $(RTL)

.PHONY: run
run: $(SIM)
	$(SIM) +TEST=$(TEST)
