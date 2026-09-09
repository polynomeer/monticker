// 종목 커뮤니티 댓글 — ADR-037. GET/POST/DELETE /api/stocks/{stockId}/comments*
export interface StockComment {
  id: number;
  stockId: number;
  eventId: number | null;
  userId: number;
  authorNickname: string;
  content: string;
  createdAt: string;
}
