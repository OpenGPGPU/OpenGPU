"""Original ESSL inputs for one continuous GPU-produced indirect grid chain."""
from pathlib import Path

CASE = 'indirect-pipeline'
GRIDS = ((2, 2, 2), (0, 0xffffffff, 0xffffffff),
         (0xffffffff, 0, 0xffffffff), (0xffffffff, 0xffffffff, 0))
PROGRAMS = ('producer-xyz.comp', 'producer-zero-x.comp', 'producer-zero-y.comp',
            'producer-zero-z.comp', 'consumer.comp')
PHASES = ((0, 0, 0), (4, 0, 0), (4, 13, 0), (4, 0, 0), (1, 0, 1),
          (4, 0, 1), (2, 0, 2), (4, 0, 2), (3, 0, 3), (4, 0, 3))


def write_sources(directory):
    directory = Path(directory)
    for name, grid in zip(PROGRAMS[:4], GRIDS):
        directory.joinpath(name).write_text('''#version 310 es
precision highp int;
layout(local_size_x=4,local_size_y=1,local_size_z=1) in;
layout(std430,binding=2) buffer Counts { uint words[]; };
void main() {
  if(gl_LocalInvocationIndex==0u) {
    words[1023]=%uu; words[1024]=%uu; words[1025]=%uu;
  }
}
''' % grid)
    directory.joinpath(PROGRAMS[4]).write_text('''#version 310 es
precision highp int;
layout(local_size_x=4,local_size_y=2,local_size_z=1) in;
layout(std430,binding=2) buffer Output { uvec4 values[]; };
void main() {
  uint group=gl_WorkGroupID.x+gl_NumWorkGroups.x*(gl_WorkGroupID.y+gl_NumWorkGroups.y*gl_WorkGroupID.z);
  uint index=group*8u+gl_LocalInvocationIndex;
  values[index]=uvec4(gl_NumWorkGroups,index);
}
''')
