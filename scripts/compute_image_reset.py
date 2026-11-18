"""Original R32F cancellation receipts and independent drained-kernel bytes.

No shader is interpreted and no returned memory is manufactured. Reset
observations are distinct from both successful and failed kernel completions.
"""
import struct
from compute_owner_protocol import uint, required_flags, RECOVERY_CASE
from compute_image_source import recipe, verify_cleaned_memory
from compute_owner_oracle import verify


def require(value, message):
    if not value:
        raise ValueError('image reset: '+message)


def reset_plan(memory, description, channel, *, owner_profile):
    from owner_resource_contract import IMAGE_FLOAT_PROFILE
    require(channel in ('read','write') and owner_profile==IMAGE_FLOAT_PROFILE and
            description.get('sourceCase')==RECOVERY_CASE,
            'response hold requires the original float buffer and complete float owner')
    recipe(memory,description)
    image=description['images'][0]
    first,size=uint(image['allocationPhysical']),uint(image['allocationBytes'])
    require(first%64==0 and size>0 and size%64==0 and first+size<=1<<32,
            'response hold is not a complete original allocation')
    return struct.pack('<8sIQQ',b'OGCRP001',1 if channel=='read' else 2,first,first+size)


def reset_history(value, channel, previous=None):
    require(channel in ('read','write'),'unknown reset response channel')
    required_flags(value,dict(physicalReadFaults=0,injectedReadFaults=0,physicalWriteFaults=0))
    counts=tuple(uint(value.get(k),64) for k in ('heldReadCycles','heldWriteCycles'))
    selected=0 if channel=='read' else 1
    require(counts[selected]>0 and counts[1-selected]==0,'selected accepted response was never withheld')
    require(previous is None or counts==reset_history(previous,channel),'reset recovery changed response-hold history')
    return counts


def _observation(value, phase, channel):
    require(phase in ('held','busy','ack','retired') and channel in ('read','write'),
            'unknown reset phase or response channel')
    drained=phase in ('ack','retired')
    required_flags(value,dict(schema='opengpu-compute-image-reset-'+phase+'-v1',phase=phase,
        record=0,channel=channel,legacyStatus=0,externalDrained=drained,completionAbsent=True,
        resetCount=1,unifiedResetRequests=0 if phase=='held' else 1,
        resetRequestTraceReplayed=phase!='held',resetTailTraceReplayed=drained,
        resetIrqRetirementReplayed=phase=='retired',busySubmitRejected=drained,noLateTrafficCycles=32 if drained else 0,
        physicalReadFaults=0,injectedReadFaults=0,physicalWriteFaults=0,readOnlyGuards=True,
        sourceQualificationRequired=True,independentBytesRequired=True,
        clockQualified=False,fpsQualified=False))
    require(not any(k in value for k in ('completion','processedBytes','gnx','heldStatus','retiredStatus')),
            'reset must not carry a kernel-completion receipt')
    command,status,irq=(uint(value.get(k)) for k in ('commandId','status','irq'))
    pin=value.get('irqAsserted')
    require(0<command<256 and type(pin) is bool,'wrong reset command ID or IRQ type')
    if phase=='held':
        require(status in (32,33) and irq in (1,3) and pin==bool(irq&2),
                'held response must retain VM authority without an owned completion')
    elif phase=='busy':
        require(status in (40,41) and irq in (0,2) and not pin,
                'original reset request lacks BUSY with IRQ delivery disabled')
    else:
        require(status==1 and irq==(3 if phase=='ack' else 1) and pin==(phase=='ack'),
                'reset ACK/IRQ retirement is not idle and drained')
    counts=reset_history(value,channel)
    uint(value.get('serviceCycles'),64)
    return command,counts


def reset_receipt_bytes(value, phase, channel, previous=None):
    command,counts=_observation(value,phase,channel)
    require((phase=='held')==(previous is None),'reset phase lacks its preceding native observation')
    if previous is not None:
        old_phase={'busy':'held','ack':'busy','retired':'ack'}[phase]
        old_command,old_counts=_observation(previous,old_phase,channel)
        require(command==old_command and all(a>=b for a,b in zip(counts,old_counts)) and
                value['serviceCycles']>=previous['serviceCycles'],
                'reset changed command identity, hold history or execution clock')
        if phase=='retired':require(counts==old_counts,'retired reset resumed response withholding')
    magic={'held':b'OGCRH001','busy':b'OGCRB001','ack':b'OGCRA001','retired':b'OGCRD001'}[phase]
    return magic+struct.pack('<6I',command,value['legacyStatus'],value['status'],value['irq'],
                             int(value['irqAsserted']),int(value['externalDrained']))


def verify_cancelled_memory(initial, returned, description, channel):
    """A held response proves kernel admission; reset drains that live kernel.

    GpuSystem blocks new host dispatch and waits for command processor,
    dispatcher and shared L2 to retire before resetting. The original host
    fence remains ECANCELED, but every original shader result must complete.
    """
    require(channel in ('read','write'),'unknown cancellation channel')
    require(description.get('sourceCase')==RECOVERY_CASE,'wrong cancelled source recipe')
    verify(initial,returned,description)


def verify_cancelled_cleanup(initial, cancelled, cleaned, description, channel, tables):
    verify_cancelled_memory(initial,cancelled,description,channel)
    verify_cleaned_memory(cancelled,cleaned,tables)
