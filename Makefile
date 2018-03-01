
FIR := ICacheCover.fir
DUT := ICache

BUILD := build
INPUT := benchmarks/$(FIR)
INSTRUMENTED := $(BUILD)/$(DUT).v
INSTRUMENTATION_TOML := $(BUILD)/$(DUT)_InstrumentationInfo.toml
TOML := $(BUILD)/$(DUT).toml
VERILATOR_HARNESS := $(BUILD)/$(DUT)_VHarness.v
FUZZ_SERVER := $(BUILD)/$(DUT)_server



default: $(VERILATOR_HARNESS)

################################################################################
# gobal clean
################################################################################
clean:
	rm -rf build/*
	rm -rf harness/*.anno
	rm -rf harness/*.fir
	rm -rf harness/*.v
	rm -rf harness/*.f
	rm -rf harness/test_run_dir

################################################################################
# instrumentation rules
################################################################################
EMPTY :=
SPACE := $(EMPTY) $(EMPTY)
COMMA := ,
FIRRTL_TRANSFORMS := \
	hardwareafl.firrtltransforms.SplitMuxConditions \
	hardwareafl.firrtltransforms.ProfilingTransform \
	firrtl.passes.wiring.WiringTransform
INSTRUMENTATION_SOURCES := $(shell find instrumentation -name '*.scala')


$(INSTRUMENTED) $(INSTRUMENTATION_TOML): $(INPUT) $(INSTRUMENTATION_SOURCES)
	cd instrumentation ;\
	sbt "runMain hardwareafl.firrtltransforms.CustomTop -i ../$< -o ../$(INSTRUMENTED) -X verilog -ll info -fct $(subst $(SPACE),$(COMMA),$(FIRRTL_TRANSFORMS))"
	mv instrumentation/$(DUT).toml $(INSTRUMENTATION_TOML)

################################################################################
# harness rules
################################################################################
HARNESS_SRC := $(shell find harness/src -name '*.scala')
HARNESS_TEST := $(shell find harness/test -name '*.scala')

$(VERILATOR_HARNESS) $(TOML): $(INSTRUMENTATION_TOML) $(HARNESS_SRC)
	cd harness ;\
	sbt "run ../$(INSTRUMENTATION_TOML) ../$(TOML)"
	mv harness/VerilatorHarness.v $(VERILATOR_HARNESS)


################################################################################
# Verilator Binary Rules
################################################################################
VERILATOR_TB_SRC = $(shell ls verilator/*.hpp verilator/*.cpp verilator/*.h verilator/*.c verilator/meson.build)
VERILATOR_BUILD = $(BUILD)/v$(DUT)


$(FUZZ_SERVER): $(TOML) $(VERILATOR_HARNESS) $(INSTRUMENTED) $(VERILATOR_TB_SRC)
	mkdir -p $(VERILATOR_BUILD)
	cd $(VERILATOR_BUILD) ;\
	meson ../../verilator --buildtype=release && \
	meson configure -Dtrace=false -Dbuild_dir='../$(BUILD)' -Ddut='$(DUT)' && \
	ninja
	mv $(VERILATOR_BUILD)/server $(FUZZ_SERVER)

################################################################################
# Fuzz Server Pseudo Target
################################################################################

run: $(FUZZ_SERVER)
	rm -rf /tmp/fpga
	mkdir /tmp/fpga
	./$(FUZZ_SERVER)
