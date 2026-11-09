# PlayBeam Server — PS5 payload
#   export PS5_PAYLOAD_SDK=/opt/ps5-payload-sdk
#   make            → playbeam-server.elf
#   make test       → sends it to the PS5 ELF loader (PS5_HOST, PS5_PORT)

PS5_HOST ?= ps5
PS5_PORT ?= 9021

ifdef PS5_PAYLOAD_SDK
    include $(PS5_PAYLOAD_SDK)/toolchain/prospero.mk
else
    $(error PS5_PAYLOAD_SDK is undefined)
endif

ELF := playbeam-server.elf

CFLAGS := -Wall -O2 -Wno-unused-function
LDLIBS := -lpthread

all: $(ELF)

$(ELF): main.c miniz.c miniz.h
	$(CC) $(CFLAGS) -o $@ main.c miniz.c $(LDLIBS)

clean:
	rm -f $(ELF)

test: $(ELF)
	$(PS5_DEPLOY) -h $(PS5_HOST) -p $(PS5_PORT) $<
