package org.wowtools.qrtransfer.sender.logic;

import org.wowtools.qrtransfer.common.pojo.FileHead;
import org.wowtools.qrtransfer.common.protobuf.QrPageProto;
import org.wowtools.qrtransfer.common.util.QRCodeUtil;
import org.wowtools.qrtransfer.common.transfer.TransferFileReader;
import org.wowtools.qrtransfer.sender.ui.SenderMainUi;

import java.io.File;
import java.util.Iterator;

/**
 * 读取文件工具
 *
 * @author liuyu
 * @date 2020/9/28
 */
public class FileReader {

    /**
     * 读取文件头信息
     *
     * @param file
     * @return
     */
    public static FileHead readHead(File file) {
        return TransferFileReader.readHead(file);
    }

    /**
     * 读取文件字节并分页为Iterator返回
     *
     * @param file
     * @return
     */
    public static Iterator<QrPageProto.QrPagePb> readPage(File file) {
        return TransferFileReader.readPages(file, Config.initQrPageSize,
                Config.testQr ? FileReader::testQrCode : null);
    }


    private static boolean testQrCode(byte[] bytes) {
        try {
            QRCodeUtil.generateQRCodeImage(bytes, SenderMainUi.qrCodeCanvas.img);
            byte[] qrBytes = QRCodeUtil.parseQRCodeImage(SenderMainUi.qrCodeCanvas.img);
            if (null == qrBytes) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }
}
