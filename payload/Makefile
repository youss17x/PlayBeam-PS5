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
TILE := playbeam-tile.elf

CFLAGS := -Wall -O2 -Wno-unused-function
LDLIBS := -lpthread -lkernel_sys

all: $(ELF) $(TILE)

$(ELF): main.c web.c qrcodegen.c miniz.c miniz.h activity.h qrcodegen.h
	$(CC) $(CFLAGS) -o $@ main.c web.c qrcodegen.c miniz.c $(LDLIBS)

# One-shot installer for the PlayBeam tile on the PS5 home screen
$(TILE): tile/main.c tile/assets/param.json tile/assets/icon0.png
	cd tile && $(CC) $(CFLAGS) -o ../$@ main.c -lSceAppInstUtil -lkernel_sys

clean:
	rm -f $(ELF) $(TILE)

test: $(ELF)
	$(PS5_DEPLOY) -h $(PS5_HOST) -p $(PS5_PORT) $<
