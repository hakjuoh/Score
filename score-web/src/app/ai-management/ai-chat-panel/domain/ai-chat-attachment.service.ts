/**
 * Validates uploaded files, converts them to chat attachments, and builds attachment summaries.
 */

import {Injectable} from '@angular/core';
import {
  SUPPORTED_ATTACHMENT_TYPES,
  TEXT_ATTACHMENT_EXTENSIONS
} from './ai-chat-panel.constants';
import {AiChatAttachment} from './ai-chat-panel.model';

@Injectable({
  providedIn: 'root'
})
export class AiChatAttachmentService {

  isSupportedAttachment(file: File, mediaType: string): boolean {
    const lowerName = file.name.toLowerCase();
    if (SUPPORTED_ATTACHMENT_TYPES.has(mediaType)
      || mediaType.startsWith('text/') || mediaType.startsWith('image/')) {
      return true;
    }
    if (mediaType && mediaType !== 'application/octet-stream') {
      return false;
    }
    return TEXT_ATTACHMENT_EXTENSIONS.some(extension => lowerName.endsWith(extension));
  }

  attachmentMediaType(file: File): string {
    const mediaType = file.type && file.type !== 'application/octet-stream' ? file.type : '';
    if (mediaType) {
      return mediaType;
    }
    const lowerName = file.name.toLowerCase();
    if (lowerName.endsWith('.tar.gz') || lowerName.endsWith('.tgz') || lowerName.endsWith('.gz')) {
      return 'application/gzip';
    }
    if (lowerName.endsWith('.zip')) {
      return 'application/zip';
    }
    if (lowerName.endsWith('.tar')) {
      return 'application/x-tar';
    }
    if (lowerName.endsWith('.json') || lowerName.endsWith('.jsonl')) {
      return 'application/json';
    }
    if (lowerName.endsWith('.xml') || lowerName.endsWith('.xsd') ||
        lowerName.endsWith('.xsl') || lowerName.endsWith('.xslt')) {
      return 'application/xml';
    }
    if (lowerName.endsWith('.csv')) {
      return 'text/csv';
    }
    if (lowerName.endsWith('.tsv')) {
      return 'text/tab-separated-values';
    }
    if (TEXT_ATTACHMENT_EXTENSIONS.some(extension => lowerName.endsWith(extension))) {
      return 'text/plain';
    }
    return 'application/octet-stream';
  }

  readAttachment(file: File, mediaType: string): Promise<AiChatAttachment> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const result = typeof reader.result === 'string' ? reader.result : '';
        const comma = result.indexOf(',');
        const data = comma >= 0 ? result.substring(comma + 1) : result;
        resolve({
          name: file.name,
          mediaType,
          size: file.size,
          data
        });
      };
      reader.onerror = () => reject();
      reader.readAsDataURL(file);
    });
  }

  userMessageContent(prompt: string, attachments: AiChatAttachment[]): string {
    const content = prompt || 'Please inspect the attached file(s).';
    if (attachments.length === 0) {
      return content;
    }
    return content + '\n' + attachments
      .map(attachment => '[Attached: ' + attachment.name + ' (' + attachment.mediaType + ')]')
      .join('\n');
  }
}
