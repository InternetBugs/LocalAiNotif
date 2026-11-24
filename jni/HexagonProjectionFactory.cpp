#include "HexagonExecutionFactory.hpp"
#include "HexagonConvolution.hpp"
#include "HexagonGatedRMSNorm.hpp"
#include "HexagonTMac.hpp"

namespace MNN {

Execution* HexagonExecutionFactory::create(const Op* op, const std::vector<Tensor*>& inputs,
                                          const std::vector<Tensor*>& outputs, Backend* backend) {
    if (op == nullptr) return nullptr;
    if (op->type() == OpType_GatedRMSNorm) {
        Execution* execution = HexagonGatedRMSNorm::create(backend, op, inputs, outputs);
        if (execution != nullptr) {
            static const bool logged = [] {
                MNN_PRINT("LocalAiNotif: Hexagon GatedRMSNorm backend active\n");
                return true;
            }();
            (void)logged;
        }
        return execution;
    }
    if (op->type() != OpType_Convolution || inputs.size() > 1) return nullptr;
    Execution* execution = HexagonTMac::create(backend, op, inputs, outputs);
    if (execution == nullptr) execution = HexagonConvolution::create(backend, op);
    if (execution != nullptr) {
        static const bool logged = [] {
            MNN_PRINT("LocalAiNotif: Hexagon projection backend active\n");
            return true;
        }();
        (void)logged;
    }
    return execution;
}

}
