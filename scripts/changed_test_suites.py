#!/usr/bin/env python3
"""Select changed Scala tests, fixture consumers, and directly matching specs.

This supplements the bounded smoke catalog; it is not a production dependency
graph. Resource-heavy integration remains in separately authorized local qualification.
"""
import json
from pathlib import Path
import re
import sys


# Keep bounded feedback when a full shared-CU integration qualifier is local.
# These checks cover configuration, descriptor ownership and image bounds;
# they do not replace the qualifier's actual shader/rendering evidence.
LOCAL_QUALIFICATION_CHECKS = {
    "opengpu.graphics.SharedShaderTaskRouterSpec": {
        "opengpu.graphics.SharedShaderTaskRouterSmallSpec",
    },
    "opengpu.core.GpuComputeUnitImageAtomicOpsSpec": {
        "opengpu.core.GpuComputeUnitImageAtomicSpec",
        "opengpu.graphics.ShaderImageAtomicAccessSpec",
    },
    "opengpu.core.memory.SharedL2CacheSpec": {
        "opengpu.core.memory.L2AtomicWriteFaultSpec",
        "opengpu.core.memory.L2MshrTableSpec",
        "opengpu.core.memory.L2StoreTransactionTableSpec",
        "opengpu.core.memory.L2VictimReservationTableSpec",
        "opengpu.core.memory.L2SimultaneousFillSharerSpec",
    },
    "opengpu.graphics.TypedBinnedFragmentImageElaborationSpec": {
        "opengpu.graphics.TypedFragmentImageStateReaderSpec",
        "opengpu.graphics.BinnedImageRoutingSpec",
    },
    "opengpu.graphics.TypedVertexBinImagePipelineSpec": {
        "opengpu.graphics.TypedFragmentImageStateReaderSpec",
        "opengpu.graphics.BinnedImageRoutingSpec",
        "opengpu.graphics.GeometryReplayFragmentAdmissionSpec",
    },
    "opengpu.graphics.TypedReplayTileImagePipelineSpec": {
        "opengpu.graphics.TypedFragmentImageStateReaderSpec",
        "opengpu.graphics.GeometryReplayFragmentAdmissionSpec",
    },
    "opengpu.graphics.TypedReplayFragmentImageElaborationSpec": {
        "opengpu.graphics.TypedFragmentImageStateReaderSpec",
        "opengpu.graphics.GraphicsImageDrawCaptureSpec",
    },
    "opengpu.graphics.TypedTileFragmentImageElaborationSpec": {
        "opengpu.graphics.GraphicsImageDrawCaptureSpec",
    },
    "opengpu.graphics.KernelShaderStageSpec": {
        "opengpu.graphics.KernelShaderStageImageRoutingSpec",
    },
    "opengpu.graphics.VertexFragmentImageAdmissionSpec": {
        "opengpu.graphics.GraphicsImageDrawCaptureSpec",
        "opengpu.graphics.KernelShaderStageImageRoutingSpec",
    },
    "opengpu.system.GpuSystemGraphicsImageSpec": {
        "opengpu.graphics.SharedShaderTaskRouterSmallSpec",
        "opengpu.dispatch.GpuImageCommandContextSpec",
        "opengpu.graphics.ShaderImageExecuteUnitSpec",
    },
    "opengpu.system.GpuSystemGraphicsImageRoutedSpec": {
        "opengpu.graphics.SharedShaderTaskRouterSmallSpec",
        "opengpu.dispatch.GpuImageCommandContextSpec",
        "opengpu.graphics.ShaderImageExecuteUnitSpec",
    },
    "opengpu.system.TessellationNativeHostElaborationSpec": {
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
    },
    "opengpu.graphics.tessellation.TessellationThreeDomainFeedbackQueryHostAxiSpec": {
        "opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec",
        "opengpu.graphics.tessellation.TessellationThreeDomainVisibilityQuerySpec",
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeThreeDomainFeedbackHostAxiSpec": {
        "opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationThreeDomainFeedbackAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec",
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeQuadHostAxiImageSpec": {
        "opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationNativeJobDomainRouterSpec",
        "opengpu.graphics.tessellation.TessellationHostCommandPortSpec",
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeHostDomainFixtureSpec": {
        "opengpu.system.TessellationNativeSequenceAbiSpec",
        "opengpu.system.TessellationNativeTriangleSequenceAbiSpec",
        "opengpu.system.TessellationNativeJobAbiSpec",
        "opengpu.system.TessellationNativeTriangleJobAbiSpec",
        "opengpu.system.TessellationNativeQuadSequenceAbiSpec",
        "opengpu.system.TessellationNativeQuadJobAbiSpec",
        "opengpu.system.TessellationNativeJobValidateSpec",
    },
    "opengpu.graphics.TessellationNativeHostLegacyCompatibilitySpec": {
        "opengpu.graphics.tessellation.TessellationHostCommandPortSpec",
        "opengpu.graphics.NativeBinnedGraphicsJobReaderSpec",
    },
    "opengpu.system.GpuHostSystemAxiSpec": {
        "opengpu.system.GpuHostAxiSpec",
        "opengpu.system.GpuHostAxiOwnershipSpec",
    },
    "opengpu.system.BufferedCommandGpuHostSystemAxiSpec": {
        "opengpu.system.GpuHostAxiSpec",
        "opengpu.system.GpuHostAxiOwnershipSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeTriangleHostAxiImageSpec": {
        "opengpu.system.TessellationNativeDomainJobImageAdmissionSpec",
        "opengpu.system.TessellationNativeDomainFeedbackAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationHostCommandPortSpec",
        "opengpu.system.TessellationNativeJobDomainRouterSpec",
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeDomainFeedbackHostAxiSpec": {
        "opengpu.system.TessellationNativeDomainFeedbackAdmissionSpec",
        "opengpu.system.TessellationDomainFeedbackAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationDomainFeedbackBridgeSpec",
        "opengpu.system.TessellationFeedbackAdmissionSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
    },
    "opengpu.system.TessellationNativeDomainJobImageAdmissionSpec": {
        "opengpu.system.TessellationNativeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationNativeJobDomainProbeSpec",
        "opengpu.system.TessellationNativeJobDomainRouterSpec",
    },
    "opengpu.system.TessellationNativeDomainFeedbackAdmissionSpec": {
        "opengpu.system.TessellationNativeDomainFeedbackElaborationSpec",
        "opengpu.system.TessellationDomainFeedbackAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationDomainFeedbackBridgeSpec",
    },
    "opengpu.system.TessellationNativeThreeDomainFeedbackAdmissionSpec": {
        "opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationThreeDomainFeedbackAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec",
    },
    "opengpu.system.TessellationNativeThreeDomainJobImageAdmissionSpec": {
        "opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationNativeJobDomainProbeSpec",
        "opengpu.system.TessellationNativeJobDomainRouterSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeTriangleJobImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationNativeTriangleJobImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationNativeTriangleJobReaderSpec",
        "opengpu.system.TessellationNativeJobMemoryFabricSpec",
        "opengpu.graphics.tessellation.TessellationNativeTriangleSequenceImageAdmissionSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeTriangleSequenceImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationNativeTriangleSequenceImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationTriangleTranslatedSequenceImageAdmissionSpec",
        "opengpu.system.TessellationNativeTriangleSequenceReaderSpec",
        "opengpu.system.TessellationNativeRecordMemoryFabricSpec",
    },
    "opengpu.graphics.tessellation.TessellationTriangleTranslatedSequenceImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationTriangleTranslatedSequenceImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationTriangleSequenceImageAdmissionSpec",
        "opengpu.system.TessellationInputMemoryFabricSpec",
        "opengpu.system.TessellationTrianglePatchSequenceSpec",
    },
    "opengpu.graphics.tessellation.TessellationTriangleSequenceImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationTriangleSequenceImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationSystemTriangleInputImageAdmissionSpec",
        "opengpu.system.TessellationTrianglePatchSequenceSpec",
        "opengpu.graphics.tessellation.TessellationTrianglePrimitiveMapperSpec",
    },
    "opengpu.graphics.tessellation.TessellationSystemTriangleInputImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationSystemTriangleInputImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationSystemTriangleImageAdmissionSpec",
        "opengpu.system.TessellationPatchInputDmaSpec",
        "opengpu.graphics.tessellation.TessellationTrianglePrimitiveMapperSpec",
    },
    "opengpu.graphics.tessellation.TessellationSystemTriangleImageSharedSystemSpec": {
        "opengpu.graphics.tessellation.TessellationSystemTriangleImageAdmissionSpec",
        "opengpu.graphics.SharedShaderTessellationRouterSpec",
        "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationTrianglePrimitiveMapperSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeQuadJobImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationNativeQuadJobImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationNativeQuadJobReaderSpec",
        "opengpu.graphics.tessellation.TessellationQuadSequenceImageElaborationSpec",
        "opengpu.system.TessellationNativeQuadSequenceReaderSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeQuadSequenceImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationNativeQuadSequenceImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationQuadSequenceImageElaborationSpec",
        "opengpu.system.TessellationNativeQuadSequenceReaderSpec",
        "opengpu.system.TessellationQuadPatchSequenceSpec",
    },
    "opengpu.system.TessellationSystemTriangleBinPassSpec": {
        "opengpu.graphics.tessellation.TessellationTrianglePrimitiveMapperSpec",
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
        "opengpu.graphics.WindowPrimitiveBinReplayPassSpec",
    },
    "opengpu.system.TessellationSystemTriangleViewportSpec": {
        "opengpu.graphics.tessellation.TessellationTrianglePrimitiveMapperSpec",
        "opengpu.graphics.tessellation.TessellationCuTriangleAdmissionSpec",
        "opengpu.graphics.PrimitiveClipStageSpec",
        "opengpu.graphics.Fp32ViewportStageSpec",
    },
    "opengpu.system.TessellationCuTriangleSharedEvaluatorSpec": {
        "opengpu.graphics.tessellation.TessellationCuTriangleAdmissionSpec",
        "opengpu.system.TessellationTrianglePatchCuEvaluationSpec",
        "opengpu.system.TessellationTriangleCuEvaluatorSpec",
    },
    "opengpu.system.TessellationTrianglePatchCuQualificationSpec": {
        "opengpu.graphics.tessellation.TessellationTrianglePatchEvaluationSpec",
        "opengpu.system.TessellationTriangleCuEvaluatorSpec",
        "opengpu.system.TessellationTrianglePatchCuEvaluationSpec",
    },
    "opengpu.system.TessellationTriangleCuQualificationSpec": {
        "opengpu.graphics.tessellation.TessellationTriangleEvaluationPipelineSpec",
        "opengpu.system.TessellationTriangleCuEvaluatorSpec",
    },
    "opengpu.system.TessellationSystemQuadFeedbackSpec": {
        "opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec",
        "opengpu.graphics.TransformFeedbackExportSpec",
        "opengpu.graphics.tessellation.TessellationCuQuadAdmissionSpec",
    },
    "opengpu.graphics.tessellation.TessellationSystemQuadInputImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationSystemQuadInputImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationSystemQuadImageAdmissionSpec",
        "opengpu.system.TessellationPatchInputDmaSpec",
        "opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec",
    },
    "opengpu.graphics.tessellation.TessellationSystemQuadImageSharedSystemSpec": {
        "opengpu.graphics.tessellation.TessellationSystemQuadImageAdmissionSpec",
        "opengpu.graphics.SharedShaderTessellationRouterSpec",
        "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec",
    },
    "opengpu.system.TessellationSystemQuadBinPassSpec": {
        "opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec",
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
        "opengpu.graphics.WindowPrimitiveBinReplayPassSpec",
    },
    "opengpu.system.TessellationSystemQuadViewportSpec": {
        "opengpu.graphics.tessellation.TessellationQuadPrimitiveMapperSpec",
        "opengpu.graphics.tessellation.TessellationCuQuadAdmissionSpec",
        "opengpu.graphics.PrimitiveClipStageSpec",
        "opengpu.graphics.Fp32ViewportStageSpec",
    },
    "opengpu.system.TessellationCuQuadSharedEvaluatorSpec": {
        "opengpu.graphics.tessellation.TessellationCuQuadAdmissionSpec",
        "opengpu.system.TessellationQuadPatchCuEvaluationSpec",
        "opengpu.system.TessellationQuadCuEvaluatorSpec",
    },
    "opengpu.system.TessellationQuadPatchCuQualificationSpec": {
        "opengpu.system.TessellationQuadPatchCuEvaluationSpec",
        "opengpu.graphics.tessellation.TessellationQuadPatchPipelineSpec",
        "opengpu.graphics.tessellation.TessellationQuadPatchEvaluationSpec",
    },
    "opengpu.system.TessellationQuadCuQualificationSpec": {
        "opengpu.system.TessellationQuadCuEvaluatorSpec",
        "opengpu.graphics.tessellation.TessellationQuadEvaluationSpec",
        "opengpu.graphics.tessellation.TessellationQuadEvaluationPipelineSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeJobImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationNativeJobImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationNativeJobReaderSpec",
        "opengpu.system.TessellationNativeJobMemoryFabricSpec",
        "opengpu.graphics.tessellation.TessellationNativeSequenceImageAdmissionSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeSequenceImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationNativeSequenceImageAdmissionSpec",
        "opengpu.system.TessellationNativeSequenceReaderSpec",
        "opengpu.system.TessellationNativeRecordMemoryFabricSpec",
        "opengpu.graphics.tessellation.TessellationTranslatedSequenceImageAdmissionSpec",
    },
    "opengpu.graphics.tessellation.TessellationTranslatedSequenceImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationTranslatedSequenceImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationSequenceImageAdmissionSpec",
        "opengpu.system.TessellationPatchSequenceSpec",
        "opengpu.system.TessellationInputMemoryFabricSpec",
    },
    "opengpu.graphics.tessellation.TessellationSequenceImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationSequenceImageAdmissionSpec",
        "opengpu.system.TessellationPatchSequenceSpec",
        "opengpu.graphics.tessellation.TessellationSystemIsolineInputImageAdmissionSpec",
        "opengpu.system.TessellationSystemIsolineInputSpec",
    },
    "opengpu.graphics.tessellation.TessellationSystemIsolineInputImageSharedSpec": {
        "opengpu.graphics.tessellation.TessellationSystemIsolineInputImageAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationSystemIsolineImageAdmissionSpec",
        "opengpu.system.TessellationPatchInputDmaSpec",
        "opengpu.system.TessellationSystemIsolineInputSpec",
    },
    "opengpu.graphics.tessellation.TessellationSystemIsolineImageSharedSystemSpec": {
        "opengpu.graphics.tessellation.TessellationSystemIsolineImageAdmissionSpec",
        "opengpu.graphics.SharedShaderTessellationRouterSpec",
        "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
        "opengpu.system.TessellationSystemIsolineBinPassSpec",
        "opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec",
    },
    "opengpu.graphics.tessellation.TessellationSampleImageSharedSystemSpec": {
        "opengpu.graphics.tessellation.TessellationSystemIsolineImageAdmissionSpec",
        "opengpu.graphics.SharedShaderTessellationRouterSpec",
        "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
        "opengpu.system.TessellationSystemIsolineBinPassSpec",
        "opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec",
        "opengpu.system.NativeOwnerHostProfileSpec",
        "opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
        "opengpu.system.TessellationImageBankOwnerSpec",
        "opengpu.system.TessellationImageBankMuxSpec",
        "opengpu.graphics.TypedFragmentImageStateReaderSpec",
        "opengpu.graphics.FragmentShaderAbiSpec",
        "opengpu.graphics.FragmentSampleVariableUnitSpec",
        "opengpu.graphics.FragmentSampleShadingSchedulerSpec",
        "opengpu.graphics.tessellation.TessellationSampleImageRecipeSpec",
    },
    "opengpu.graphics.tessellation.TessellationSampleImageOpsSharedSystemSpec": {
        "opengpu.graphics.tessellation.TessellationSystemIsolineImageAdmissionSpec",
        "opengpu.graphics.SharedShaderTessellationRouterSpec",
        "opengpu.graphics.GeometryFragmentTessellationRouterSpec",
        "opengpu.system.TessellationSystemIsolineBinPassSpec",
        "opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec",
        "opengpu.system.NativeOwnerHostProfileSpec",
        "opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec",
        "opengpu.system.TessellationHostShaderRouterSpec",
        "opengpu.system.TessellationImageBankOwnerSpec",
        "opengpu.system.TessellationImageBankMuxSpec",
        "opengpu.graphics.TypedFragmentImageStateReaderSpec",
        "opengpu.graphics.FragmentShaderAbiSpec",
        "opengpu.graphics.FragmentSampleVariableUnitSpec",
        "opengpu.graphics.FragmentSampleShadingSchedulerSpec",
        "opengpu.graphics.tessellation.TessellationSampleImageRecipeSpec",
        "opengpu.graphics.ShaderImageExecuteUnitSpec",
        "opengpu.graphics.ShaderImagePublicationReaderSpec",
        "opengpu.graphics.ShaderImageStoreEncoderSpec",
        "opengpu.core.frontend.decode.ImageExecutionAvailabilityDecodeSpec",
        "opengpu.core.frontend.decode.FullInstructionDecoderSpec",
    },
    "opengpu.graphics.TypedGeometryImageQualificationSpec": {
        "opengpu.graphics.TypedTileImageTransferSpec",
        "opengpu.graphics.GeometryReplayFragmentAdmissionSpec",
        "opengpu.graphics.FragmentShaderStageSpec",
    },
    "opengpu.graphics.TypedTileImagePipelineSpec": {
        "opengpu.graphics.TypedTileImageTransferSpec",
        "opengpu.graphics.FragmentShaderStageSpec",
    },
    "opengpu.graphics.TypedTileRenderPipelineSpec": {
        "opengpu.graphics.TypedTileImageTransferSpec",
        "opengpu.graphics.FragmentShaderStageSpec",
    },
    "opengpu.system.ControlledIsolineSharedCuViewportQualificationSpec": {
        "opengpu.system.ControlledIsolineSharedCuViewportAdmissionSpec",
        "opengpu.system.ControlledIsolineSharedCuClipAdmissionSpec",
        "opengpu.graphics.Fp32ViewportStageSpec",
    },
    "opengpu.system.ControlledIsolineWindowBinPassSpec": {
        "opengpu.system.ControlledIsolineWindowBinAdmissionSpec",
        "opengpu.system.ControlledIsolineSharedCuViewportAdmissionSpec",
    },
    "opengpu.graphics.ControlledIsolineReplayFragmentPipelineSpec": {
        "opengpu.graphics.ControlledIsolineReplayFragmentAdmissionSpec",
        "opengpu.graphics.ControlledIsolineReplayRasterClientSpec",
    },
    "opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec": {
        "opengpu.system.ControlledIsolineWindowBinAdmissionSpec",
        "opengpu.graphics.TypedTileImageTransferSpec",
    },
    "opengpu.graphics.ControlledIsolineWindowBinTypedImageSharedSystemSpec": {
        "opengpu.graphics.ControlledIsolineWindowBinTypedImagePipelineSpec",
        "opengpu.system.ControlledIsolineWindowBinAdmissionSpec",
        "opengpu.graphics.TypedTileImageTransferSpec",
    },
    "opengpu.system.ControlledIsolineSharedCuClipQualificationSpec": {
        "opengpu.system.ControlledIsolineSharedCuClipAdmissionSpec",
        "opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec",
        "opengpu.graphics.PrimitiveClipStageSpec",
    },
    "opengpu.system.ControlledIsolineSharedCuRendererQualificationSpec": {
        "opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec",
        "opengpu.system.IsolinePatchInputReaderSpec",
        "opengpu.graphics.tessellation.TessellationControlledIsolineRendererSpec",
        "opengpu.system.IsolineGeometryCuExecutorSpec",
    },
    "opengpu.system.IsolinePatchSharedCuQualificationSpec": {
        "opengpu.system.IsolinePatchSharedCuAdmissionSpec",
        "opengpu.graphics.tessellation.TessellationIsolinePatchEvaluationSpec",
        "opengpu.system.IsolineGeometryCuExecutorSpec",
    },
    "opengpu.graphics.PreparedGeometryFragmentPipelineSpec": {
        "opengpu.graphics.FragmentShaderStageSpec",
        "opengpu.graphics.GeometryReplayFragmentAdmissionSpec",
        "opengpu.graphics.FragmentInputCoalescerSpec",
    },
    "opengpu.graphics.VertexFragmentPipelineSpec": {
        "opengpu.graphics.FragmentShaderStageSpec",
        "opengpu.graphics.GeometryFragmentAdmissionSpec",
        "opengpu.graphics.FragmentInputCoalescerSpec",
    },
    "opengpu.graphics.GeometryReplayFragmentPipelineSpec": {
        "opengpu.graphics.GeometryReplayFragmentAdmissionSpec",
    },
    "opengpu.graphics.GeometryWindowBinPassSpec": {
        "opengpu.graphics.GeometryWindowBinAdmissionSpec",
    },
    "opengpu.graphics.VertexBinPipelineSpec": {
        "opengpu.graphics.WindowPrimitiveRecordsSpec",
        "opengpu.graphics.WindowPrimitiveBinProducerSpec",
    },
    "opengpu.graphics.GeometryFragmentDrawPipelineSpec": {
        "opengpu.graphics.GeometryFragmentDrawStreamSpec",
        "opengpu.graphics.GeometryFragmentAdmissionSpec",
    },
    "opengpu.graphics.GeometryFragmentPipelineSpec": {
        "opengpu.graphics.GeometryFragmentAdmissionSpec",
    },
    "opengpu.graphics.FragmentShaderStageQualificationSpec": {
        "opengpu.graphics.FragmentShaderStageSpec",
        "opengpu.graphics.FragmentInputCoalescerSpec",
    },
    "opengpu.graphics.FragmentReturnedCreditQualificationSpec": {
        "opengpu.graphics.FragmentShaderStageSpec",
    },
    "opengpu.system.GpuHostAtomicCounterCommandSpec": {
        "opengpu.system.GpuHostAxiSpec",
        "opengpu.graphics.GpuCommandMmioAtomicCounterSpec",
    },
    "opengpu.system.GeometryPrimitiveRasterClientSpec": {
        "opengpu.system.GeometryRasterAdmissionSpec",
    },
    "opengpu.system.GpuSystemAtomicCounterSpec": {
        "opengpu.dispatch.AtomicCounterGridDispatchSpec",
        "opengpu.dispatch.AtomicCounterCommandOwnershipSpec",
        "opengpu.core.memory.AtomicCounterVectorCuServiceSpec",
    },
    "opengpu.system.GpuSystemStorageAtomicSpec": {
        "opengpu.core.memory.StorageAtomicVectorCuServiceSpec",
        "opengpu.core.frontend.decode.StorageAtomicAvailabilityDecodeSpec",
    },
    "opengpu.system.GpuHostStorageAtomicCommandSpec": {
        "opengpu.core.memory.StorageAtomicVectorCuServiceSpec",
        "opengpu.core.frontend.decode.StorageAtomicAvailabilityDecodeSpec",
    },
    "opengpu.graphics.GpuHostComputeTextureCommandSpec": {
        "opengpu.system.GpuHostAxiSpec",
        "opengpu.graphics.GpuCommandMmioComputeTextureSpec",
    },
    "opengpu.graphics.MemoryNativeBinnedGraphicsPipelineSpec": {
        "opengpu.graphics.NativeRenderTargetExtentSpec",
        "opengpu.graphics.NativeBinnedGraphicsJobReaderSpec",
        "opengpu.graphics.StagedTypedImageAdmissionSpec",
    },
    "opengpu.system.GpuSystemSpec": {
        "opengpu.system.GpuHostAxiSpec", "opengpu.system.AxiStimulusOrderingSpec",
    },
    "opengpu.graphics.PersistentTileSharedSystemSpec": {
        "opengpu.graphics.TileCommandPassControllerSpec", "opengpu.graphics.TileBinReplaySchedulerSpec",
    },
    "opengpu.graphics.TileBinRenderPipelineSpec": {
        "opengpu.graphics.TileBinRenderPassAdmissionSpec", "opengpu.graphics.TileBinJobReaderSpec",
    },
    "opengpu.graphics.VertexShaderStageSpec": {
        "opengpu.graphics.VertexShaderClearLinesSpec", "opengpu.graphics.TextureLaneStartSpec",
        "opengpu.graphics.VertexInputCoalescerSpec",
    },
    "opengpu.graphics.FragmentShaderStageSpec": {
        "opengpu.graphics.FragmentInputCoalescerSpec", "opengpu.graphics.FragmentShaderAbiSpec",
        "opengpu.graphics.FragmentMetadataReaderSpec",
    },
    "opengpu.system.GpuHostAxiSpec": {
        "opengpu.system.GpuHostAxiOwnershipSpec", "opengpu.graphics.GpuCommandMmioSpec",
    },
    "opengpu.graphics.tessellation.TessellationQuadSequenceImageAdmissionSpec": {
        "opengpu.system.TessellationQuadPatchSequenceSpec",
        "opengpu.graphics.tessellation.TessellationQuadSequenceImageElaborationSpec",
    },
    "opengpu.graphics.tessellation.TessellationQuadTranslatedSequenceImageAdmissionSpec": {
        "opengpu.system.TessellationQuadPatchSequenceSpec",
        "opengpu.system.TessellationPatchInputDmaSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeQuadSequenceImageAdmissionSpec": {
        "opengpu.system.TessellationNativeQuadSequenceReaderSpec",
        "opengpu.system.TessellationNativeQuadSequenceAbiSpec",
    },
    "opengpu.graphics.tessellation.TessellationNativeQuadJobImageAdmissionSpec": {
        "opengpu.graphics.tessellation.TessellationNativeQuadJobReaderSpec",
        "opengpu.system.TessellationNativeQuadJobAbiSpec",
    },
}


def local_only_suites(root):
    path = root / "scripts/ci_local_only_suites.txt"
    if not path.exists():
        return set()
    return {line.strip() for line in path.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith("#")}


def is_local_only_suite(root, suite):
    # These graphics host suites elaborate a full GPU even for tiny frames.
    return (suite in local_only_suites(root) or
            (suite.startswith("opengpu.graphics.") and suite.endswith("HostAxiSpec")))


def index_test_sources(root):
    """Parse one snapshot of the test tree for repeated selection queries."""
    test_root = root / "src/test/scala"
    files = {}
    nodes = []
    for path in sorted(test_root.rglob("*.scala")):
        source = path.read_text()
        # Ignore comments so a documentation mention cannot create dependencies.
        source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        declarations = list(re.finditer(
            r"^((?:(?:abstract|final|sealed)\s+)*)(class|trait|object)\s+(\w+)",
            source, re.M,
        ))
        file_nodes = set()
        for index, declaration in enumerate(declarations):
            modifiers, kind, name = declaration.groups()
            end = declarations[index + 1].start() if index + 1 < len(declarations) else len(source)
            references = set(re.findall(r"\b\w+\b", source[declaration.end():end])) - {name}
            suite = (package[1] + "." + name
                     if package and kind == "class" and "abstract" not in modifiers
                     and name.endswith("Spec") else None)
            file_nodes.add(len(nodes))
            nodes.append((name, references, suite))
        files[path.relative_to(root).as_posix()] = file_nodes
    return files, nodes


def changed_suites(root, changed, *, test_index=None):
    """Select suites; an explicit index must be a snapshot of this root."""
    changed = set(changed)
    files, nodes = index_test_sources(root) if test_index is None else test_index

    affected = set(changed) & files.keys()
    if "src/main/scala/opengpu/graphics/ClipFloatArithmetic.scala" in changed:
        # The scalar arithmetic service has four production owners. Its
        # response latency also reaches the spatial preparation/credit paths.
        affected.update("src/test/scala/opengpu/graphics/" + name + ".scala" for name in (
            "ClipFloatArithmeticSpec", "PrimitiveClipStageSpec", "Fp32ViewportStageSpec",
            "Fp32PrimitiveRasterStageSpec", "PrimitiveRasterInstanceSpec",
            "Fp32FragmentInterpolatorSpec", "Fp32FragmentPreparationSpec",
            "Fp32StreamingFragmentInterpolatorSpec", "FragmentSetupCacheSpec",
            "FragmentPreparationInstanceSpec"))
    if changed & {"src/main/scala/opengpu/graphics/Fp32FragmentInterpolator.scala",
                  "src/main/scala/opengpu/graphics/Fp32FragmentPreparation.scala",
                  "src/main/scala/opengpu/graphics/Fp32StreamingFragmentInterpolator.scala"}:
        affected.add("src/test/scala/opengpu/graphics/FragmentSetupCacheSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/FragmentPreparationInstanceSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/Fp32FragmentPreparationSpec.scala")
    if "src/main/scala/opengpu/graphics/TypedFragmentState.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/TypedFragmentStateReaderSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TypedFragmentImageStateReaderSpec.scala")
    if changed & {"src/main/scala/opengpu/graphics/WindowPrimitiveRecordReadCache.scala",
                  "src/main/scala/opengpu/graphics/WindowPrimitiveRecords.scala",
                  "src/main/scala/opengpu/graphics/WindowPrimitiveBinReplayPass.scala",
                  "src/main/scala/opengpu/elaboration/EmitWindowPrimitiveRecordReadCache.scala"}:
        affected.add("src/test/scala/opengpu/graphics/WindowPrimitiveRecordReadCacheSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/WindowPrimitiveBinReplayPassSpec.scala")
    if "src/test/scala/opengpu/graphics/tessellation/TessellationSampleImageRecipeSpec.scala" in changed:
        # The recipe object is package-private; follow its two explicit users
        # without broadening the generic suite-discovery parser.
        affected.update("src/test/scala/opengpu/graphics/tessellation/" + name + ".scala" for name in (
            "TessellationSampleImageSharedSystemSpec", "TessellationSampleImageOpsSharedSystemSpec"))
    ci_inputs = {
        ".github/actions/setup-simulation/action.yml", "scripts/prefetch_scala_bridge.sh",
        ".github/workflows/scala_test.yml", "scripts/changed_test_suites.py",
        "scripts/check_pr_test_results.py", "scripts/ci_shard_tests.sh",
        "scripts/check_ci_job_results.sh",
        "scripts/ci_pr_suites.txt", "scripts/ci_local_only_suites.txt", "scripts/select_tests.sh",
        "scripts/ci_case_partitions.py", "scripts/ci_changed_host_cases.py", "scripts/ci_telemetry_coverage.py", "scripts/run_ci_partition.py",
        "src/test/scala/opengpu/testutil/CiSuiteCases.scala",
        ".github/workflows/scala_regression.yml",
    }
    if changed & ci_inputs:
        # Exercise the actual required matrix when its own plumbing changes.
        affected.add("src/test/scala/opengpu/testutil/GpuSimSpec.scala")
    if changed & {"src/main/scala/opengpu/graphics/tessellation/TessellationLevels.scala",
                  "src/main/scala/opengpu/graphics/tessellation/TessellationEdgeCoordinate.scala"}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTriangleInteriorSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeQuadSequenceReader.scala",
                  "src/main/scala/opengpu/system/TessellationQuadPatchSequence.scala",
                  "src/main/scala/opengpu/elaboration/EmitTessellationNativeQuadSequenceAbi.scala",
                  "driver/opengpu_native_tessellation_quad_sequence_abi.h",
                  "driver/tests/opengpu_tessellation_quad_sequence_abi_test.c",
                  "docs/abi/native-tessellation-quad-sequence-v3.json"}:
        affected.add("src/test/scala/opengpu/system/TessellationNativeQuadSequenceAbiSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeQuadSequenceReaderSpec.scala")
    if "src/main/scala/opengpu/system/TessellationQuadSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadSequenceImageElaborationSpec.scala")
    if "src/main/scala/opengpu/system/TessellationQuadTranslatedSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadTranslatedSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadSequenceImageElaborationSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeQuadSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeQuadSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadSequenceImageElaborationSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeQuadSequenceReaderSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeQuadJobImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeQuadJobImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeQuadJobReaderSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadSequenceImageElaborationSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeQuadJobReader.scala",
                  "src/main/scala/opengpu/system/TessellationNativeQuadSequenceImageSubmit.scala"}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeQuadJobReaderSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeQuadJobReader.scala",
                  "src/main/scala/opengpu/elaboration/EmitTessellationNativeQuadJobAbi.scala",
                  "driver/opengpu_native_tessellation_quad_job_abi.h",
                  "driver/tests/opengpu_tessellation_quad_job_abi_test.c",
                  "docs/abi/native-tessellation-quad-job-v3.json"}:
        affected.add("src/test/scala/opengpu/system/TessellationNativeQuadJobAbiSpec.scala")
    if changed & {f"src/main/scala/opengpu/graphics/tessellation/{name}.scala" for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleBoundary",
            "TessellationTriangleInterior", "TessellationTriangleStrip")}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTriangleGeneratorSpec.scala")
    if changed & {f"src/main/scala/opengpu/graphics/tessellation/{name}.scala" for name in ("TessellationLevels", "TessellationEdgeCoordinate", "TessellationTriangleBoundary",
            "TessellationTriangleInterior", "TessellationTriangleStrip", "TessellationTriangleGenerator",
            "TessellationTriangleEvaluation")}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTriangleEvaluationPipelineSpec.scala")
    if changed & {f"src/main/scala/opengpu/system/{name}.scala" for name in (
            "IsolinePatchInputReader", "IsolineGeometryCuExecutor")}:
        affected.add("src/test/scala/opengpu/system/TessellationTriangleCuEvaluatorSpec.scala")
    if changed & {f"src/main/scala/opengpu/graphics/tessellation/{name}.scala" for name in ("TessellationPatchContext", "TessellationTrianglePatchPipeline", "TessellationTriangleEvaluation",
            "TessellationTriangleGenerator")}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTrianglePatchEvaluationSpec.scala")
    if changed & ({f"src/main/scala/opengpu/graphics/tessellation/{name}.scala" for name in ("TessellationPatchContext", "TessellationTrianglePatchPipeline", "TessellationTrianglePatchEvaluation")} |
            {f"src/main/scala/opengpu/system/{name}.scala" for name in (
            "TessellationTriangleCuEvaluator", "TessellationTrianglePatchCuEvaluation",
            "IsolinePatchInputReader", "IsolineGeometryCuExecutor")}):
        affected.add("src/test/scala/opengpu/system/TessellationTrianglePatchCuEvaluationSpec.scala")
    if changed & {f"src/main/scala/opengpu/graphics/tessellation/{name}.scala" for name in ("TessellationCuTrianglePatch", "TessellationControlWarpScheduler", "TessellationTriangleGenerator",
            "TessellationTriangleEvaluation")}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationCuTriangleAdmissionSpec.scala")
    if "src/main/scala/opengpu/system/TessellationCuTriangleSharedEvaluator.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationCuTriangleAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationTrianglePatchCuEvaluationSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationCuTriangleSystemClient.scala",
                  "src/main/scala/opengpu/system/TessellationSystemTriangleViewport.scala",
                  "src/main/scala/opengpu/graphics/tessellation/TessellationTrianglePrimitiveMapper.scala"}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTrianglePrimitiveMapperSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationCuTriangleAdmissionSpec.scala")
    if "src/main/scala/opengpu/system/TessellationSystemTriangleBinPass.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/GeometryWindowBinAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/WindowPrimitiveBinReplayPassSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTrianglePrimitiveMapperSpec.scala")
    if "src/main/scala/opengpu/system/TessellationSystemTriangleImagePipeline.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationSystemTriangleImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/GeometryFragmentTessellationRouterSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTrianglePrimitiveMapperSpec.scala")
    if "src/main/scala/opengpu/system/TessellationSystemTriangleInputImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationSystemTriangleInputImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationPatchInputDmaSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationSystemTriangleInputImage.scala",
                  "src/main/scala/opengpu/system/TessellationTrianglePatchSequence.scala"}:
        affected.add("src/test/scala/opengpu/system/TessellationTrianglePatchSequenceSpec.scala")
    if "src/main/scala/opengpu/system/TessellationTriangleSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTriangleSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationTrianglePatchSequenceSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationTriangleTranslatedSequenceImage.scala",
                  "src/main/scala/opengpu/system/TessellationInputMemoryFabric.scala"}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTriangleTranslatedSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationInputMemoryFabricSpec.scala")
    if changed & {"src/main/scala/opengpu/elaboration/EmitNativeGeometryAbi.scala",
                  "src/main/scala/opengpu/graphics/NativeGeometryDescriptors.scala",
                  "src/main/scala/opengpu/graphics/NativeGeometryProgramReader.scala",
                  "driver/opengpu_native_geometry_abi.h", "docs/abi/native-geometry-v1.json"}:
        affected.add("src/test/scala/opengpu/graphics/NativeGeometryProgramSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/NativeBinnedGraphicsJobReaderSpec.scala")
    if changed & {"src/main/scala/opengpu/elaboration/EmitGeometryUniformAbi.scala",
                  "driver/opengpu_geometry_uniform_abi.h", "docs/abi/geometry-uniform-v2.json"}:
        affected.add("src/test/scala/opengpu/graphics/ComputeUniformRecordAbiSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/ShaderUniformLoadUnitSpec.scala")
    if changed & {"src/main/scala/opengpu/elaboration/EmitNativeUniformAbi.scala",
                  "driver/opengpu_uniform_abi.h", "docs/abi/native-uniform-v1.json"}:
        affected.add("src/test/scala/opengpu/graphics/ComputeUniformRecordAbiSpec.scala")
    if changed & {"src/main/scala/opengpu/system/ComputeResourceCapabilities.scala",
                  "src/main/scala/opengpu/elaboration/EmitComputeResourceAbi.scala",
                  "driver/opengpu_compute_resource_abi.h", "docs/abi/compute-resource-v1.json"}:
        for suite in ("ShaderImageCommandAbiSpec", "StorageCommandAbiSpec", "ComputeUniformCommandAbiSpec"):
            affected.add(f"src/test/scala/opengpu/graphics/{suite}.scala")
    if changed & {"src/main/scala/opengpu/system/OwnerResourceProfile.scala",
                  "src/test/scala/opengpu/system/PrepareOwnerResourceHost.scala",
                  "src/main/scala/opengpu/system/NativeOwnerHostProfile.scala",
                  "src/test/scala/opengpu/system/PrepareNativeOwnerHost.scala",
                  "src/main/scala/opengpu/system/NativeAlignmentHostProfile.scala",
                  "src/main/scala/opengpu/system/ComputeResourceCapabilities.scala",
                  "src/main/scala/opengpu/system/NativeShaderExecutionCapabilities.scala"}:
        affected.add("src/test/scala/opengpu/system/NativeOwnerHostProfileSpec.scala")
    if changed & {"src/main/scala/opengpu/graphics/NativeAdvancedBlendCapabilities.scala",
                  "src/main/scala/opengpu/system/NativeAlignmentHostProfile.scala",
                  "src/main/scala/opengpu/elaboration/EmitNativeAdvancedBlendAbi.scala",
                  "driver/opengpu_native_advanced_blend_abi.h", "driver/opengpu_native_advanced_blend_features.h",
                  "driver/opengpu_native_advanced_blend_gfs.h", "driver/tests/opengpu_native_advanced_blend_gfs_test.c",
                  "driver/tests/opengpu_native_advanced_blend_kernel_abi_test.c",
                  "driver/tests/opengpu_native_advanced_blend_discovery_test.c"}:
        affected.add("src/test/scala/opengpu/graphics/NativeAdvancedBlendAbiSpec.scala")
        affected.add("src/test/scala/opengpu/system/NativeAdvancedBlendCapabilitiesAxiSpec.scala")
    if changed & {"src/main/scala/opengpu/graphics/NativeDescriptorPreflightCapabilities.scala",
                  "src/main/scala/opengpu/graphics/NativeBinnedDescriptorPreflight.scala",
                  "src/main/scala/opengpu/elaboration/EmitNativeDescriptorPreflightAbi.scala",
                  "driver/opengpu_native_preflight_abi.h", "driver/opengpu_native_preflight_features.h",
                  "driver/tests/opengpu_native_preflight_discovery_test.c"}:
        affected.add("src/test/scala/opengpu/graphics/NativeAdvancedBlendAbiSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/NativeBinnedGraphicsJobReaderSpec.scala")
        affected.add("src/test/scala/opengpu/system/NativeDescriptorPreflightCapabilitiesAxiSpec.scala")
    if changed & {"src/main/scala/opengpu/graphics/NativeGeometryTransportCapabilities.scala",
                  "src/main/scala/opengpu/elaboration/EmitNativeGeometryTransportAbi.scala",
                  "driver/opengpu_native_geometry_transport_abi.h",
                  "driver/opengpu_native_geometry_transport_features.h",
                  "driver/tests/opengpu_native_geometry_transport_discovery_test.c"}:
        affected.add("src/test/scala/opengpu/system/NativeGeometryTransportCapabilitiesAxiSpec.scala")
    if changed & {"src/main/scala/opengpu/graphics/NativeSampleTransportCapabilities.scala",
                  "src/main/scala/opengpu/elaboration/EmitNativeSampleTransportAbi.scala",
                  "driver/opengpu_native_sample_transport_abi.h",
                  "driver/opengpu_native_sample_transport_features.h",
                  "driver/tests/opengpu_native_sample_transport_test.c",
                  "src/test/scala/opengpu/system/PrepareOwnerResourceSampleHost.scala"}:
        affected.add("src/test/scala/opengpu/system/NativeGeometryTransportCapabilitiesAxiSpec.scala")
        affected.add("src/test/scala/opengpu/system/NativeOwnerHostProfileSpec.scala")
    if changed & {"src/test/scala/opengpu/system/NativeLayeredProductFixtures.scala",
                  "scripts/native_layered_product_fixture.hpp",
                  "scripts/native_layered_product_execution.hpp",
                  "scripts/native_layered_product_driver.cpp",
                  "scripts/native_sample_layered_product_driver.cpp",
                  "scripts/qualify_native_layered_product.py",
                  "scripts/qualify_native_sample_layered_product.py"}:
        affected.add("src/test/scala/opengpu/system/NativeLayeredProductFixturesSpec.scala")
        affected.add("src/test/scala/opengpu/system/NativeSampleLayeredProductFixturesSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/NativeLayeredProductFrameInputsSpec.scala")
    texture_image_abi_paths = {
        f"src/main/scala/opengpu/elaboration/Emit{name}Abi.scala"
        for name in ("ShaderImage", "TextureImageBinding", "TextureImageLevel", "TextureImageFormat")
    } | {
        f"driver/opengpu_{name}_abi.h"
        for name in ("shader_image", "texture_image_binding", "texture_image_level", "texture_image_format")
    } | {
        f"docs/abi/{name}-v1.json"
        for name in ("shader-image", "texture-image-binding", "texture-image-level", "texture-image-format")
    } | {
        f"src/main/scala/opengpu/graphics/{name}.scala"
        for name in ("ShaderImageDescriptorReader", "TextureImageAddressUnit", "TextureImageBindingUnit",
                     "TextureImageResourceAddressUnit", "TextureImageFormat")
    }
    if changed & texture_image_abi_paths:
        affected.add("src/test/scala/opengpu/graphics/ShaderImageAbiSpec.scala")
    tessellation_record_abi_paths = {
        f"src/main/scala/opengpu/elaboration/EmitTessellationNative{domain}{kind}Abi.scala"
        for domain in ("", "Triangle", "Quad") for kind in ("Job", "Sequence")
    } | {
        f"driver/opengpu_native_tessellation{domain}_{kind}_abi.h"
        for domain in ("", "_triangle", "_quad") for kind in ("job", "sequence")
    } | {"driver/tests/opengpu_tessellation_record_kernel_abi_test.c",
         "driver/tests/tessellation_record_kernel_stubs/linux/types.h"}
    if changed & tessellation_record_abi_paths:
        affected.add("src/test/scala/opengpu/system/TessellationNativeRecordKernelAbiSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeJobValidateSpec.scala")
    if changed & {"src/main/scala/opengpu/elaboration/EmitNativeTextureAbi.scala",
                  "driver/opengpu_native_texture_abi.h", "docs/abi/native-texture-v1.json"}:
        affected.add("src/test/scala/opengpu/system/TessellationNativeRecordKernelAbiSpec.scala")
    # The validator C fixture and kernel-header syntax check run directly in
    # the host driver suite. Avoid compiling Scala solely to invoke them again.
    if changed & {"driver/opengpu_native_binned_abi.h",
                  "src/main/scala/opengpu/elaboration/EmitNativeBinnedGraphicsAbi.scala"}:
        affected.add("src/test/scala/opengpu/system/TessellationNativeJobValidateSpec.scala")
    if "driver/tests/opengpu_tessellation_job_fixture_test.c" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeHostDomainFixtureSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeTriangleSequenceReader.scala",
                  "src/main/scala/opengpu/elaboration/EmitTessellationNativeTriangleSequenceAbi.scala",
                  "src/main/scala/opengpu/system/TessellationTrianglePatchSequence.scala"}:
        affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleSequenceReaderSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleSequenceAbiSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationDomainFeedbackBridge.scala",
                  "src/main/scala/opengpu/system/TessellationDomainFeedbackAdmission.scala"}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationDomainFeedbackBridgeSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/TransformFeedbackOwnerTransferSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationDomainSampleMux.scala",
                  "src/main/scala/opengpu/graphics/NativeVisibilityQueryEngine.scala",
                  "src/main/scala/opengpu/system/TessellationNativeDomainJobImage.scala",
                  "src/main/scala/opengpu/system/GpuHostSystemAxi.scala"}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationDomainVisibilityQuerySpec.scala")
    if "src/main/scala/opengpu/system/TessellationHostPayloadOwner.scala" in changed:
        affected.add("src/test/scala/opengpu/system/TessellationThreeDomainPayloadOwnerSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationThreeDomainFeedbackBridge.scala",
                  "src/main/scala/opengpu/system/TessellationThreeDomainFeedbackAdmission.scala",
                  "src/main/scala/opengpu/graphics/TransformFeedbackThreeDomainTransfer.scala"}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationThreeDomainFeedbackBridgeSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeThreeDomainJobImageElaborationSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationThreeDomainFeedbackAdmission.scala",
                  "src/main/scala/opengpu/system/TessellationThreeDomainPayloadOwner.scala",
                  "src/main/scala/opengpu/system/TessellationNativeThreeDomainJobImage.scala"}:
        affected.add("src/test/scala/opengpu/system/TessellationFeedbackPayloadAdmissionSpec.scala")
    if "src/main/scala/opengpu/system/TessellationThreeDomainSampleMux.scala" in changed:
        affected.add("src/test/scala/opengpu/system/TessellationNativeThreeDomainJobImageElaborationSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationThreeDomainSampleMux.scala",
                  "src/main/scala/opengpu/system/TessellationNativeThreeDomainJobImage.scala",
                  "src/main/scala/opengpu/system/GpuHostSystemAxi.scala",
                  "src/main/scala/opengpu/graphics/NativeVisibilityQueryEngine.scala"}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationThreeDomainVisibilityQuerySpec.scala")
    if "src/main/scala/opengpu/system/TessellationHostMemoryFabric.scala" in changed:
        affected.add("src/test/scala/opengpu/system/TessellationThreeDomainMemoryFabricSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeThreeDomainJobImage.scala" in changed:
        affected.add("src/test/scala/opengpu/system/TessellationNativeThreeDomainJobImageElaborationSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeJobDomainRouterSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationImageBankOwner.scala",
                  "src/main/scala/opengpu/system/TessellationImageBankMux.scala",
                  "src/main/scala/opengpu/system/TessellationNativeThreeDomainJobImage.scala",
                  "src/main/scala/opengpu/graphics/TypedReplayTileImagePipeline.scala"}:
        affected.add("src/test/scala/opengpu/system/TessellationImageBankSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeDomainJobImage.scala" in changed:
        affected.add("src/test/scala/opengpu/system/TessellationNativeDomainJobImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeDomainJobImageElaborationSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeDomainFeedbackAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeDomainFeedbackElaborationSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeTriangleJobImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeTriangleJobImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeTriangleJobReaderSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeJobMemoryFabricSpec.scala")
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeTriangleSequenceImageAdmissionSpec.scala")
    if "src/main/scala/opengpu/system/TessellationNativeTriangleSequenceImage.scala" in changed:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeTriangleSequenceImageAdmissionSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleSequenceReaderSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeRecordMemoryFabricSpec.scala")
    if changed & {"src/main/scala/opengpu/system/TessellationNativeTriangleJobReader.scala",
                  "src/main/scala/opengpu/elaboration/EmitTessellationNativeTriangleJobAbi.scala",
                  "src/main/scala/opengpu/system/TessellationNativeTriangleSequenceReader.scala",
                  "driver/opengpu_native_tessellation_triangle_job_abi.h",
                  "driver/tests/opengpu_tessellation_triangle_job_abi_test.c",
                  "docs/abi/native-tessellation-triangle-job-v2.json"}:
        affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeTriangleJobReaderSpec.scala")
        affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleJobAbiSpec.scala")
    for name in changed:
        if name in ("driver/opengpu_native_tessellation_triangle_sequence_abi.h",
                    "docs/abi/native-tessellation-triangle-sequence-v2.json"):
            affected.add("src/test/scala/opengpu/system/TessellationNativeTriangleSequenceAbiSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/tessellation/TessellationLevels.scala",
                    "src/main/scala/opengpu/graphics/tessellation/TessellationEdgeCoordinate.scala"):
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTriangleBoundarySpec.scala")
        if name == "src/main/scala/opengpu/system/GpuHostAxi.scala":
            affected.add("src/test/scala/opengpu/system/TessellationHostAxiCommandSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/TileBinHostCommandPort.scala",
                    "src/main/scala/opengpu/command/GpuCommandRouter.scala"):
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationHostCommandPortSpec.scala")
        if name in ("src/main/scala/opengpu/system/TessellationNativeJobReader.scala",
                    "src/main/scala/opengpu/system/TessellationNativeSequenceReader.scala",
                    "src/main/scala/opengpu/graphics/NativeBinnedGraphicsDescriptors.scala",
                    "src/main/scala/opengpu/elaboration/EmitTessellationNativeJobAbi.scala",
                    "driver/opengpu_native_tessellation_job_abi.h",
                    "driver/tests/opengpu_tessellation_job_abi_test.c",
                    "docs/abi/native-tessellation-job-v1.json"):
            affected.add("src/test/scala/opengpu/system/TessellationNativeJobAbiSpec.scala")
        if name in ("src/main/scala/opengpu/elaboration/EmitTessellationNativeCompletionAbi.scala",
                    "src/main/scala/opengpu/system/TessellationNativeCompletionInfo.scala",
                    "driver/opengpu_native_tessellation_completion_abi.h",
                    "driver/opengpu_tessellation_completion.h",
                    "driver/tests/opengpu_tessellation_completion_test.c",
                    "driver/tests/opengpu_tessellation_completion_kernel_test.c",
                    "driver/tests/tessellation_abi_stubs/linux/types.h",
                    "scripts/test_tessellation_completion_kernel.sh",
                    "docs/abi/native-tessellation-completion-v1.json"):
            affected.add("src/test/scala/opengpu/system/TessellationNativeCompletionInfoSpec.scala")
        if name in ("driver/opengpu_native_tessellation_sequence_abi.h",
                    "docs/abi/native-tessellation-sequence-v1.json"):
            affected.add("src/test/scala/opengpu/system/TessellationNativeSequenceAbiSpec.scala")
        if name in {"src/main/scala/opengpu/graphics/tessellation/" + unit + ".scala" for unit in (
                "TessellationLevels", "TessellationEdgeCoordinate", "TessellationQuadBoundary",
                "TessellationQuadInterior", "TessellationQuadStrip", "TessellationQuadGenerator")}:
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadGeneratorSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadEvaluationPipelineSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadPatchEvaluationSpec.scala")
            affected.add("src/test/scala/opengpu/system/TessellationQuadPatchCuEvaluationSpec.scala")
        if name == "src/main/scala/opengpu/graphics/tessellation/TessellationQuadEvaluation.scala":
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadEvaluationPipelineSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/tessellation/TessellationQuadEvaluation.scala",
                    "src/main/scala/opengpu/system/IsolinePatchInputReader.scala",
                    "src/main/scala/opengpu/system/IsolineGeometryCuExecutor.scala"):
            affected.add("src/test/scala/opengpu/system/TessellationQuadCuEvaluatorSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/tessellation/TessellationPatchContext.scala",
                    "src/main/scala/opengpu/graphics/tessellation/TessellationQuadPatchPipeline.scala",
                    "src/main/scala/opengpu/graphics/tessellation/TessellationQuadEvaluation.scala",
                    "src/main/scala/opengpu/graphics/tessellation/TessellationQuadPatchEvaluation.scala",
                    "src/main/scala/opengpu/system/TessellationQuadCuEvaluator.scala"):
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadPatchEvaluationSpec.scala")
            affected.add("src/test/scala/opengpu/system/TessellationQuadPatchCuEvaluationSpec.scala")
        if name == "src/main/scala/opengpu/system/TessellationSystemQuadInputImage.scala":
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationSystemQuadInputImageAdmissionSpec.scala")
            affected.add("src/test/scala/opengpu/system/TessellationPatchInputDmaSpec.scala")
        if name == "src/main/scala/opengpu/system/TessellationSystemQuadImagePipeline.scala":
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationSystemQuadImageAdmissionSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/GeometryFragmentTessellationRouterSpec.scala")
        if name == "src/main/scala/opengpu/system/TessellationSystemQuadBinPass.scala":
            affected.add("src/test/scala/opengpu/graphics/GeometryWindowBinAdmissionSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/WindowPrimitiveBinReplayPassSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadPrimitiveMapperSpec.scala")
        if name in ("src/main/scala/opengpu/system/TessellationCuQuadSystemClient.scala",
                    "src/main/scala/opengpu/system/TessellationSystemQuadViewport.scala",
                    "src/main/scala/opengpu/graphics/tessellation/TessellationQuadPrimitiveMapper.scala"):
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadPrimitiveMapperSpec.scala")
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationCuQuadAdmissionSpec.scala")
        if name in ("src/main/scala/opengpu/graphics/tessellation/TessellationCuQuadPatch.scala",
                    "src/main/scala/opengpu/system/TessellationCuQuadSharedEvaluator.scala"):
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationCuQuadAdmissionSpec.scala")
            affected.add("src/test/scala/opengpu/system/TessellationQuadPatchCuEvaluationSpec.scala")
        if name == "src/main/scala/opengpu/graphics/tessellation/TessellationPatchContext.scala":
            affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationQuadPatchPipelineSpec.scala")
        if name.startswith("src/main/scala/") and name.endswith(".scala"):
            if name in ("src/main/scala/opengpu/system/TessellationNativeSequenceReader.scala",
                        "src/main/scala/opengpu/elaboration/EmitTessellationNativeSequenceAbi.scala"):
                affected.add("src/test/scala/opengpu/system/TessellationNativeSequenceAbiSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationNativeJobImage.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeJobImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationNativeJobReader.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeJobReaderSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationNativeSequenceImage.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationNativeSequenceImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationTranslatedSequenceImage.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationTranslatedSequenceImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationSequenceImage.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationSequenceImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationSystemIsolineInputImage.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationSystemIsolineInputImageSharedSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationPatchInputDma.scala":
                affected.add("src/test/scala/opengpu/system/TessellationPatchInputDmaContextSpec.scala")
            if name == "src/main/scala/opengpu/system/TessellationSystemIsolineImagePipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationSystemIsolineImageSharedSystemSpec.scala")
            if name == "src/main/scala/opengpu/graphics/SharedShaderTaskRouter.scala":
                affected.add("src/test/scala/opengpu/graphics/SharedShaderTessellationRouterSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/GeometryFragmentTessellationRouterSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryFragmentShaderRouter.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryFragmentTessellationRouterSpec.scala")
            if name == "src/main/scala/opengpu/graphics/tessellation/TessellationCuIsolinePatch.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationCuIsolineAdmissionSpec.scala")
            if name == "src/main/scala/opengpu/graphics/tessellation/TessellationCuBarrier.scala":
                affected.add("src/test/scala/opengpu/core/GpuComputeUnitTessellationSpec.scala")
            if name == "src/main/scala/opengpu/graphics/tessellation/TessellationCuFinish.scala":
                affected.add("src/test/scala/opengpu/core/GpuComputeUnitTessellationSpec.scala")
            if name == "src/main/scala/opengpu/graphics/tessellation/TessellationControlVectorAccess.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationControlVectorContextSpec.scala")
                affected.add("src/test/scala/opengpu/core/GpuComputeUnitTessellationSpec.scala")
                affected.add("src/test/scala/opengpu/core/frontend/decode/TessellationExecutionAvailabilitySpec.scala")
            if name == "src/main/scala/opengpu/graphics/tessellation/TessellationWarpPatchAccess.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationWarpPatchContextSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineSharedCuViewport.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuViewportAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuClipAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/Fp32ViewportStageSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineWindowBinPass.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineWindowBinPassSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineWindowBinTypedImagePipelineSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineWindowBinTypedImagePipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineWindowBinTypedImagePipelineSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineWindowBinTypedImageSharedSystemSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineSharedCuClip.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuClipAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuRendererAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/PrimitiveClipStageSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineSharedCuRenderer.scala":
                affected.add("src/test/scala/opengpu/system/ControlledIsolineSharedCuRendererAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/system/IsolinePatchInputReaderSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationControlledIsolineRendererSpec.scala")
                affected.add("src/test/scala/opengpu/system/IsolineGeometryCuExecutorSpec.scala")
            if name == "src/main/scala/opengpu/system/IsolinePatchSharedCu.scala":
                affected.add("src/test/scala/opengpu/system/IsolinePatchSharedCuAdmissionSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationIsolinePatchEvaluationSpec.scala")
                affected.add("src/test/scala/opengpu/system/IsolineGeometryCuExecutorSpec.scala")
            if name == "src/main/scala/opengpu/graphics/tessellation/TessellationIsolinePatchPipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/tessellation/TessellationIsolinePatchCancellationSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryReplayFragmentPipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryReplayFragmentPipelineSpec.scala")
            if name == "src/main/scala/opengpu/system/ControlledIsolineReplayFragmentPipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineReplayFragmentPipelineSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryReplayRasterClient.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryReplayRasterClientSpec.scala")
                affected.add("src/test/scala/opengpu/graphics/ControlledIsolineReplayRasterClientSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryWindowBinPass.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryWindowBinPassSpec.scala")
            if name == "src/main/scala/opengpu/system/GeometryFragmentDrawPipeline.scala":
                affected.add("src/test/scala/opengpu/graphics/GeometryFragmentDrawPipelineSpec.scala")
            candidate = name.replace("src/main/scala/", "src/test/scala/", 1)
            candidate = candidate[:-6] + "Spec.scala"
            if candidate in files:
                affected.add(candidate)
    # Include inherited suites and consumers of shared test fixtures, even if
    # the fixture filename itself does not end in Spec.scala. References are
    # deliberately conservative across packages (extra coverage is safe).
    selected = set().union(*(files[path] for path in affected if path in files))
    while True:
        symbols = {nodes[index][0] for index in selected}
        consumers = {index for index, (_, references, _) in enumerate(nodes)
                     if references & symbols}
        expanded = selected | consumers
        if expanded == selected:
            break
        selected = expanded
    suites = {nodes[index][2] for index in selected if nodes[index][2]}
    if any(s.startswith("opengpu.graphics.") and s.endswith("HostAxiSpec") for s in suites):
        # Exercise small ownership and command components when full-host tests
        # or their shared fixtures change. Full rendering remains local.
        suites.update({"opengpu.graphics.NativeVisibilityQueryEngineSpec",
                       "opengpu.graphics.NativePrimitiveQueryEngineSpec",
                       "opengpu.graphics.NativeTransformFeedbackControlSpec",
                       "opengpu.graphics.TileBinHostQueryCommandPortSpec",
                       "opengpu.graphics.NativeBinnedGraphicsJobReaderSpec",
                       "opengpu.graphics.NativeRenderTargetExtentSpec",
                       "opengpu.graphics.StagedTypedImageAdmissionSpec"})
    for qualification, checks in LOCAL_QUALIFICATION_CHECKS.items():
        if qualification in suites:
            suites.update(checks)
    return sorted(suite for suite in suites if not is_local_only_suite(root, suite))


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    deferred = sorted(local_only_suites(root))
    if deferred:
        print("Local-only qualification outside CI: " + ", ".join(deferred), file=sys.stderr)
    print(json.dumps(changed_suites(root, sys.argv[1:]), separators=(",", ":")))
