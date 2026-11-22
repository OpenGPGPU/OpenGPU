"""Original compute cancellation receipts and independent accepted-work bytes.

No shader is interpreted and no returned memory is manufactured. Reset
observations are distinct from both successful and failed kernel completions.
"""
import struct
from compute_owner_protocol import uint, required_flags, reset_case_supported
from compute_p12_counter_cases import COMBINED
from compute_image_source import recipe, texel_address, atomic_values, verify_cleaned_memory


def require(value, message):
    if not value:
        raise ValueError('image reset: '+message)


def reset_plan(memory, description, channel, *, owner_profile):
    from owner_resource_contract import IMAGE_FLOAT_PROFILE
    case = description.get('sourceCase')
    require(owner_profile==IMAGE_FLOAT_PROFILE and reset_case_supported(case,channel),
            'response hold requires a supported live recipe and complete float owner')
    if case == COMBINED:
        from compute_p12_counter_source import recipe as combined_recipe
        from compute_p12_texture_source import TEXTURE_TABLE_PAGES
        combined_recipe(memory,description)
        # Every texel fetch needs this table. The workgroup barrier precedes
        # the fetches, and all output stores depend on their results.
        first,size=description['texturePhysical'],TEXTURE_TABLE_PAGES
    else:
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


def _cancel_words(initial, description, channel):
    p,output,images=recipe(initial,description)
    words={};sources=set()
    def word(address):return struct.unpack('<I',initial.read(address,4))[0]
    def grant(address,values):
        require(address%4==0 and address not in words,'cancel word is unaligned or aliased')
        words[address]=set(values)
    for invocation in range(8):
        source=texel_address(images[0],p,0,invocation)
        old,final=atomic_values(p,word(source),invocation)
        # Every source R response is hidden before exposure in a cold DUT.
        # Only the first exchange can have been offered; its accepted atomic
        # may finish the write while cancellation suppresses vector commit.
        grant(source,old[:2] if channel=='read' else [*old,final]);sources.add(source)
        if channel=='write':
            destination=texel_address(images[1],p,1,invocation)
            grant(destination,[word(destination),final])
            for access,value in enumerate(old):
                address=output+4*(len(old)*invocation+access)
                grant(address,[word(address),value])
    return words,sources


def verify_cancelled_memory(initial, returned, description, channel):
    """Keep only original program states at the actual cancellation cut.

    Reset cancels CU image/storage/uniform services while waiting for their
    accepted operations to drain. An accepted atomic may write its source
    even when its result is suppressed. No CPU result or memory is created.
    """
    case = description.get('sourceCase')
    require(reset_case_supported(case,channel),'unsupported cancellation recipe or channel')
    if case == COMBINED:
        from compute_owner_oracle import verify_byte_changes
        from compute_p12_counter_source import recipe as combined_recipe, final_counter_bytes
        source, _, _, _ = combined_recipe(initial,description)
        final = final_counter_bytes()
        verify_byte_changes(initial,returned,[(source,final)])
        return dict(changedSourceWords=len(final)//4)
    words,sources=_cancel_words(initial,description,channel)
    require(initial.ranges==returned.ranges,'cancellation replaced original RAM')
    for first,end,_ in initial.ranges:
        for address in range(first,end,4096):
            count=min(4096,end-address)
            old,new=initial.read(address,count),returned.read(address,count)
            if old==new:continue
            require(address%4==0 and count%4==0,'cancellation changed a partial allocation word')
            for offset in range(0,count,4):
                a,b=struct.unpack_from('<I',old,offset)[0],struct.unpack_from('<I',new,offset)[0]
                if a==b:continue
                physical=address+offset
                require(physical in words and b in words[physical],
                    'invalid cancelled original word: physical=0x%x before=0x%08x after=0x%08x allowed=%s'%(
                        physical,a,b,[hex(v) for v in sorted(words.get(physical,()))]))
    if channel=='write':
        require(any(initial.read(a,4)!=returned.read(a,4) for a in sources),
                'write-response hold lacks committed original source data')
    return dict(changedSourceWords=sum(initial.read(a,4)!=returned.read(a,4) for a in sources))


def verify_cancelled_cleanup(initial, cancelled, cleaned, description, channel, tables):
    verify_cancelled_memory(initial,cancelled,description,channel)
    verify_cleaned_memory(cancelled,cleaned,tables)
